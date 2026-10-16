package dev.teo.mydb.plugin.grid

import com.intellij.database.connection.throwable.info.SimpleErrorInfo
import com.intellij.database.datagrid.*
import com.intellij.database.datagrid.mutating.CellMutation
import com.intellij.database.datagrid.mutating.MutationData
import com.intellij.database.run.ReservedCellValue
import com.intellij.database.run.ui.grid.GridMutationModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.containers.JBIterable
import dev.teo.mydb.core.edit.DefaultValue
import dev.teo.mydb.core.edit.GridEdits
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.core.edit.UserText
import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.core.exec.Session
import java.sql.Types

/** How a result is (re)loaded: the query for a page at [offset] with [limit] rows. */
fun interface PageQuery { fun load(session: Session, offset: Int, limit: Int): ResultPage }

/** Exact number of rows of a result, for "Count Rows". */
fun interface RowCounter { fun count(session: Session): Long }

/** Where a grid reports submitted statements; [error] lines are shown in red. */
fun interface GridLog { fun line(text: String, error: Boolean) }

/** Target table of an editable result. */
data class EditTarget(val schema: String, val table: String, val keyColumns: List<String>, val columnsWithDefault: Set<String>,
                      val database: String? = null)

/**
 * Connects the community data grid to a [Session]: paging through [PageQuery], cell edits recorded
 * as pending changes and submitted with [GridEdits].
 */
class JdbcGridHookUp(
    project: Project,
    val session: Session,
    private val query: PageQuery,
    val editTarget: EditTarget?,
    initial: ResultPage? = null,
    private val counter: RowCounter? = null,
    /** Output log of the owning session: submitted statements and their outcome. */
    private val log: GridLog? = null,
    /** Key of the owning console or table editor session in ConsoleService (Tx and DDL buttons). */
    val sessionKey: String? = null,
) : GridDataHookUpBase<GridRow, GridColumn>(project) {

    private val model = DataGridListModel { a, b -> a == b }
    /** Quoting of generated DML follows the session's DBMS. */
    private val dialect by lazy { dev.teo.mydb.core.exec.SqlDialect.of(session.connection) }
    private val mutationModel = GridMutationModel(this)
    private var page: ResultPage? = null
    private var offset = 0
    private var currentPageSize = session.pageSize
    /** Counted total, once "Count Rows" ran; dropped when the result is reloaded with other filters. */
    @Volatile var countedRows: Long? = null
        private set
    private val mutator = if (editTarget != null) Mutator() else null
    var lastSubmit: SubmitResult? = null
        private set
    /** Called on the EDT after each submit. */
    val submitListeners = mutableListOf<(SubmitResult) -> Unit>()

    init { initial?.let { applyPage(it, 0) } }

    override fun getDataModel(): GridModel<GridRow, GridColumn> = model
    override fun getMutationModel(): GridModel<GridRow, GridColumn> = mutationModel
    override fun getMutator(): GridMutator<GridRow, GridColumn>? = mutator
    override fun isReadOnly() = editTarget == null

    override fun getPageModel(): GridPagingModel<GridRow, GridColumn> = object : GridPagingModelImpl<GridRow, GridColumn>(model) {
        override fun isFirstPage() = offset == 0
        override fun isLastPage() = page?.hasMore != true
        override fun setPageSize(size: Int) { currentPageSize = size; session.pageSize = size }
        override fun getPageSize() = currentPageSize
        override fun pageSizeSet() = true
        override fun getTotalRowCount(): Long = countedRows ?: (offset + model.rowCount + if (page?.hasMore == true) 1 else 0).toLong()
        override fun isTotalRowCountPrecise() = countedRows != null || page?.hasMore != true
        override fun isTotalRowCountUpdateable() = counter != null
        override fun getPageStart() = offset + 1
        override fun getPageEnd() = offset + model.rowCount
    }

    override fun getLoader(): GridLoader = object : GridLoader {
        override fun reloadCurrentPage(source: GridRequestSource) = loadAt(source, offset)
        override fun loadNextPage(source: GridRequestSource) = loadAt(source, offset + currentPageSize)
        override fun loadPreviousPage(source: GridRequestSource) = loadAt(source, maxOf(0, offset - currentPageSize))
        override fun loadLastPage(source: GridRequestSource) = loadAt(source, offset)
        override fun loadFirstPage(source: GridRequestSource) = loadAt(source, 0)
        override fun load(source: GridRequestSource, offset: Int) = loadAt(source, offset)
        override fun updateTotalRowCount(source: GridRequestSource) = countRows(source)
        override fun applyFilterAndSorting(source: GridRequestSource) { countedRows = null; loadAt(source, 0) }
        override fun updateIsTotalRowCountUpdateable() {}
    }

    /** Runs the counter off the EDT and shows the exact total. */
    fun countRows(source: GridRequestSource) {
        val c = counter ?: return
        notifyRequestStarted(source)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { c.count(session) }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { countedRows = it; notifyRequestFinished(source, true) }
                    .onFailure { notifyRequestError(source, SimpleErrorInfo.create(it)); notifyRequestFinished(source, false) }
            }
        }
    }

    private fun loadAt(source: GridRequestSource, newOffset: Int) {
        notifyRequestStarted(source)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { query.load(session, newOffset, currentPageSize) }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { applyPage(it, newOffset); notifyRequestFinished(source, true) }
                    .onFailure { notifyRequestError(source, SimpleErrorInfo.create(it)); notifyRequestFinished(source, false) }
            }
        }
    }

    private fun applyPage(p: ResultPage, newOffset: Int) {
        page = p
        offset = newOffset
        mutator?.clear()
        model.setUpdatingNow(true)
        try {
            val columns = p.columns.mapIndexed { i, c -> DataConsumer.Column(i, c.label, c.jdbcType, c.typeName, javaClassName(c.jdbcType), c.precision, c.scale, null, c.baseSchema, c.baseTable) }
            model.setColumns(columns)
            model.removeRows(0, model.rowCount)
            model.addRows(p.rows.mapIndexed { i, r -> DataConsumer.Row.create(newOffset + i, r.copyOf() as Array<Any?>) })
        } finally {
            model.setUpdatingNow(false)
        }
    }

    /** Current page as core data, used for DML generation and export. */
    fun currentPage(): ResultPage? = page

    // ---- pending changes ---------------------------------------------------------------------

    inner class Mutator : GridMutator.DatabaseMutator<GridRow, GridColumn> {
        private val updates = linkedMapOf<Int, MutableMap<Int, Any?>>()
        private val deleted = linkedSetOf<Int>()
        private val inserted = mutableListOf<Array<Any?>>()
        /** Grid row whose statement failed in the last submit; only that row is shown in red. */
        var failedRow: Int? = null
            private set

        fun clear() {
            val added = insertedRange()
            updates.clear(); deleted.clear(); inserted.clear(); failedRow = null
            if (added.isNotEmpty()) mutationModel.notifyRowsRemoved(rowSet(added))
        }

        private fun insertedRange() = (dataRows() until dataRows() + inserted.size).toList()
        private fun rowSet(rows: Collection<Int>) = ModelIndexSet.forRows(mutationModel, *rows.toIntArray())

        private fun dataRows() = model.rowCount
        private fun isInsertedIdx(r: Int) = r >= dataRows()

        override fun isUpdateSafe(rowIndices: ModelIndexSet<GridRow>, columnIndices: ModelIndexSet<GridColumn>, newValue: Any?) = true
        override fun hasPendingChanges() = updates.isNotEmpty() || deleted.isNotEmpty() || inserted.isNotEmpty()
        override fun hasUnparsedValues() = false
        override fun hasUnparsedValues(row: ModelIndex<GridRow>) = false
        override fun isUpdateImmediately() = false
        // the community colour layer paints every pending row red while failed; the failed row is painted by FailedRowColorLayer
        override fun isFailed() = false

        private fun resetFailure() {
            val r = failedRow ?: return
            failedRow = null
            if (r < mutationModel.rowCount) cellsChanged(listOf(r))
        }

        override fun mutate(source: GridRequestSource, row: ModelIndexSet<GridRow>, column: ModelIndexSet<GridColumn>, newValue: Any?, allowImmediateUpdate: Boolean) {
            resetFailure()
            for (r in row.asIterable()) for (c in column.asIterable()) set(r.value, c.value, newValue)
            cellsChanged(row.asIterable().map { it.value }.toList(), column.asIterable().map { it.value }.toList())
            notifyRequestFinished(source, true)
        }

        override fun mutate(source: GridRequestSource, mutations: List<CellMutation>, allowImmediateUpdate: Boolean) {
            resetFailure()
            for (m in mutations) set(m.row.value, m.column.value, m.value)
            cellsChanged(mutations.map { it.row.value }.distinct(), mutations.map { it.column.value }.distinct())
            notifyRequestFinished(source, true)
        }

        private fun set(r: Int, c: Int, value: Any?) {
            val v = if (value is String) UserText(value) else value
            if (isInsertedIdx(r)) { inserted[r - dataRows()][c] = v; return }
            val original = model.getValueAt(ModelIndex.forRow(model, r), ModelIndex.forColumn(model, c))
            val cell = updates.getOrPut(r) { linkedMapOf() }
            if (original == value) cell.remove(c) else cell[c] = v
            if (cell.isEmpty()) updates.remove(r)
        }

        /** Tells the view which cells changed so it repaints them (pending colours, new values). */
        private fun cellsChanged(rows: Collection<Int>, cols: Collection<Int>? = null) {
            val c = cols ?: (0 until mutationModel.columnCount).toList()
            mutationModel.notifyCellsUpdated(rowSet(rows), ModelIndexSet.forColumns(mutationModel, *c.toIntArray()), null)
        }

        override fun deleteRows(source: GridRequestSource, rows: ModelIndexSet<GridRow>) {
            resetFailure()
            val removed = mutableListOf<Int>(); val marked = mutableListOf<Int>()
            for (r in rows.asIterable().map { it.value }.sortedDescending()) {
                if (isInsertedIdx(r)) { inserted.removeAt(r - dataRows()); removed += r } else { deleted += r; updates.remove(r); marked += r }
            }
            if (removed.isNotEmpty()) mutationModel.notifyRowsRemoved(rowSet(removed))
            if (marked.isNotEmpty()) cellsChanged(marked)
            notifyRequestFinished(source, true)
        }

        override fun insertRows(source: GridRequestSource, amount: Int) {
            resetFailure()
            val cols = page?.columns ?: return
            val first = dataRows() + inserted.size
            repeat(amount) {
                inserted += Array(cols.size) { i -> if ((cols[i].baseColumn ?: cols[i].label) in editTarget!!.columnsWithDefault) DefaultValue else null }
            }
            mutationModel.notifyRowsAdded(rowSet((first until first + amount).toList()))
            mutationModel.afterLastRowAdded()
            notifyRequestFinished(source, true)
        }

        override fun cloneRow(source: GridRequestSource, toClone: ModelIndex<GridRow>) {
            val r = toClone.value
            val values = if (isInsertedIdx(r)) inserted[r - dataRows()].copyOf()
                         else Array(model.columnCount) { c -> model.getValueAt(toClone, ModelIndex.forColumn(model, c)) }
            inserted += values
            mutationModel.notifyRowsAdded(rowSet(listOf(dataRows() + inserted.size - 1)))
            mutationModel.afterLastRowAdded()
            notifyRequestFinished(source, true)
        }

        override fun isDeletedRow(row: ModelIndex<GridRow>) = row.value in deleted
        override fun isDeletedRows(rows: ModelIndexSet<GridRow>) = rows.asIterable().all { it.value in deleted }
        override fun isInsertedRow(row: ModelIndex<GridRow>) = isInsertedIdx(row.value)
        override fun getInsertedRowsCount() = inserted.size
        override fun getLastInsertedRow(): ModelIndex<GridRow> = ModelIndex.forRow(model, dataRows() + inserted.size - 1)
        override fun getAffectedRows(): ModelIndexSet<GridRow> =
            ModelIndexSet.forRows(model, *(updates.keys + deleted + (0 until inserted.size).map { it + dataRows() }).toIntArray())
        override fun getInsertedRows(): JBIterable<ModelIndex<GridRow>> =
            JBIterable.from((0 until inserted.size).map { ModelIndex.forRow(model, dataRows() + it) })

        override fun getMutationType(row: ModelIndex<GridRow>): MutationType? = when {
            isInsertedIdx(row.value) -> MutationType.INSERT
            row.value in deleted -> MutationType.DELETE
            row.value in updates -> MutationType.MODIFY
            else -> null
        }
        override fun getMutationType(row: ModelIndex<GridRow>, column: ModelIndex<GridColumn>): MutationType? = when {
            isInsertedIdx(row.value) -> MutationType.INSERT
            row.value in deleted -> MutationType.DELETE
            updates[row.value]?.containsKey(column.value) == true -> MutationType.MODIFY
            else -> null
        }

        override fun getMutation(row: ModelIndex<GridRow>, column: ModelIndex<GridColumn>): MutationData? {
            if (isInsertedIdx(row.value)) return inserted.getOrNull(row.value - dataRows())?.getOrNull(column.value)?.let { MutationData(display(it)) } ?: MutationData(null)
            val cell = updates[row.value] ?: return null
            return if (cell.containsKey(column.value)) MutationData(display(cell[column.value])) else null
        }

        private fun display(v: Any?): Any? = when (v) {
            is UserText -> v.text
            DefaultValue -> ReservedCellValue.DEFAULT
            else -> v
        }

        override fun hasMutatedRows(rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) =
            rows.asIterable().any { getMutationType(it) != null }

        override fun revert(source: GridRequestSource, rows: ModelIndexSet<GridRow>, columns: ModelIndexSet<GridColumn>) {
            resetFailure()
            val cols = columns.asIterable().map { it.value }.toSet()
            val removed = mutableListOf<Int>(); val changed = mutableListOf<Int>()
            for (r in rows.asIterable().map { it.value }.sortedDescending()) {
                when {
                    isInsertedIdx(r) -> { inserted.removeAt(r - dataRows()); removed += r }
                    r in deleted -> { deleted.remove(r); changed += r }
                    else -> updates[r]?.let { cell -> cols.forEach(cell::remove); if (cell.isEmpty()) updates.remove(r); changed += r }
                }
            }
            if (removed.isNotEmpty()) mutationModel.notifyRowsRemoved(rowSet(removed))
            if (changed.isNotEmpty()) cellsChanged(changed)
            notifyRequestFinished(source, true)
        }

        override fun getPendingChanges(): String = toEdits()?.previewText() ?: ""

        /** Pending changes as core edits. */
        fun toEdits(): GridEdits? {
            val p = page ?: return null
            val t = editTarget ?: return null
            val e = GridEdits(t.schema, t.table, p, t.keyColumns, t.columnsWithDefault, dialect)
            updates.forEach { (r, cells) -> cells.forEach { (c, v) -> e.setValue(r, c, v) } }
            deleted.forEach(e::deleteRow)
            inserted.forEach { values -> val i = e.addRow(); values.forEachIndexed { c, v -> e.setInsertedValue(i, c, v) } }
            return e
        }

        override fun submit(source: GridRequestSource, includeInserted: Boolean) {
            val edits = toEdits() ?: return
            notifyRequestStarted(source)
            ApplicationManager.getApplication().executeOnPooledThread {
                val started = System.currentTimeMillis()
                val result = edits.submit(session)
                logSubmit(edits, result, System.currentTimeMillis() - started)
                ApplicationManager.getApplication().invokeLater {
                    lastSubmit = result
                    submitListeners.toList().forEach { it(result) }
                    when (result) {
                        is SubmitResult.Ok -> { clear(); notifyRequestFinished(source, true); loadAt(source, offset) }
                        is SubmitResult.Failed -> {
                            failedRow = result.statement.row.takeIf { it >= 0 }
                            failedRow?.let { cellsChanged(listOf(it)) }
                            notifyRequestError(source, SimpleErrorInfo.create(java.sql.SQLException(
                                "[${result.sqlState ?: ""}] ${result.message}", result.sqlState)))
                            notifyRequestFinished(source, false)
                        }
                    }
                }
            }
        }

        /** Statements up to the failing one, then the outcome. */
        private fun logSubmit(edits: GridEdits, result: SubmitResult, millis: Long) {
            val l = log ?: return
            val stmts = edits.statements()
            val sent = when (result) {
                is SubmitResult.Ok -> stmts
                is SubmitResult.Failed -> stmts.subList(0, stmts.indexOf(result.statement).let { if (it < 0) stmts.size else it + 1 })
            }
            sent.forEach { l.line(it.display.replace(Regex("\\s*\n\\s*"), " "), false) }
            when (result) {
                is SubmitResult.Ok -> l.line("Submitted ${result.statements} ${if (result.statements == 1) "change" else "changes"} in $millis ms", false)
                is SubmitResult.Failed -> l.line((result.sqlState?.let { "[$it] " } ?: "") + result.message.lineSequence().first() +
                    " — the transaction was rolled back; changes are still pending", true)
            }
        }

        // columns are not added/removed from query results
        override fun deleteColumns(source: GridRequestSource, columns: ModelIndexSet<GridColumn>) {}
        override fun insertColumn(source: GridRequestSource, name: String?) {}
        override fun cloneColumn(source: GridRequestSource, toClone: ModelIndex<GridColumn>) {}
        override fun getInsertedColumnsCount() = 0
        override fun getInsertedColumns(): JBIterable<ModelIndex<GridColumn>> = JBIterable.empty()
        override fun isInsertedColumn(idx: ModelIndex<GridColumn>) = false
        override fun isDeletedColumn(idx: ModelIndex<GridColumn>) = false
        override fun renameColumn(source: GridRequestSource, idx: ModelIndex<GridColumn>, newName: String) {}
        override fun getInsertedColumn(idx: ModelIndex<GridColumn>): GridColumn? = null
    }

    companion object {
        fun javaClassName(jdbcType: Int): String = when (jdbcType) {
            Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "java.lang.Integer"
            Types.BIGINT -> "java.lang.Long"
            Types.NUMERIC, Types.DECIMAL -> "java.math.BigDecimal"
            Types.REAL, Types.FLOAT, Types.DOUBLE -> "java.lang.Double"
            Types.BOOLEAN, Types.BIT -> "java.lang.Boolean"
            Types.DATE -> "java.sql.Date"
            Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "java.sql.Timestamp"
            else -> "java.lang.String"
        }
    }
}
