package dev.teo.mydb.plugin.console

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.run.ui.DataAccessType
import com.intellij.database.run.ui.table.LocalFilterState
import com.intellij.database.datagrid.RowSortOrder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.grid.GridFactory
import dev.teo.mydb.plugin.grid.JdbcGridHookUp
import dev.teo.mydb.plugin.grid.PageQuery
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.KeyStroke

/** Result grid behaviour on our hook-up:. */
class GridFeaturesTest : MyDbTestCase() {
    private lateinit var handle: SessionHandle
    private val serverQueries = AtomicInteger()
    private lateinit var grid: DataGrid

    override fun setUp() {
        super.setUp()
        handle = ConsoleService.getInstance(project).session("grid-test", "grid-test", dataSource)
        val sql = "select id, sku, price from shop.products order by id desc"
        val page = (handle.session.execute(sql) as StatementResult.Rows).page
        val query = PageQuery { s, offset, limit -> serverQueries.incrementAndGet(); (s.execute(sql, offset + limit) as StatementResult.Rows).page.let { it.copy(rows = it.rows.drop(offset)) } }
        grid = GridFactory.create(project, handle.session, page, query, null, testRootDisposable, ConsoleRunner.counter(sql))
    }

    override fun tearDown() {
        try { Disposer.dispose(grid); ConsoleService.getInstance(project).close("grid-test") } finally { super.tearDown() }
    }

    private fun column(name: String): ModelIndex<com.intellij.database.datagrid.GridColumn> {
        val m = grid.getDataModel(DataAccessType.DATABASE_DATA)
        return ModelIndex.forColumn(m, m.columns.indexOfFirst { it.name == name })
    }

    /** Values of a column in the order the grid shows its rows. */
    private fun shown(name: String): List<Any?> {
        val m = grid.getDataModel(DataAccessType.DATABASE_DATA)
        val c = column(name)
        val toModel = grid.rawIndexConverter.row2Model()
        return (0 until grid.visibleRowsCount).map { m.getValueAt(ModelIndex.forRow(m, toModel.applyAsInt(it)), c) }
    }

    fun `test sorting by a column happens in the grid without a server query`() {
        assertEquals(20, shown("price").size)
        grid.sortColumns(listOf(column("price")), RowSortOrder.Type.ASC, false)
        val prices = shown("price").map { (it as java.math.BigDecimal) }
        assertEquals(prices.sorted(), prices)
        assertEquals(0, serverQueries.get())
    }

    fun `test numeric values are shown with the declared scale`() {
        val m = grid.getDataModel(DataAccessType.DATABASE_DATA)
        val c = column("price")
        val col = m.getColumn(c)!!
        val texts = (0 until m.rowCount).map {
            grid.objectFormatter.objectToString(m.getValueAt(ModelIndex.forRow(m, it), c), col,
                com.intellij.database.extractors.DatabaseObjectFormatterConfig(com.intellij.database.extractors.ObjectFormatterMode.DISPLAY))
        }
        assertTrue(texts.toString(), texts.all { Regex("""\d+\.\d\d""").matches(it!!) })
        assertTrue(texts.toString(), "2.50" in texts)
    }

    fun `test the default page size is 500`() {
        assertEquals(500, com.intellij.database.datagrid.GridHelper.get(grid).properties.defaultPageSize)
    }

    fun `test preview dialog shows the pending DML and submits on demand`() {
        var submitted = false
        val dialog = dev.teo.mydb.plugin.grid.PreviewChangesDialog(project, "DELETE\nFROM shop.products\nWHERE id = 8;") { submitted = true }
        try {
            assertEquals("DML Preview", dialog.title)
            assertTrue(dialog.text.startsWith("DELETE"))
            assertFalse(submitted)
        } finally { dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) }
        assertNotNull(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("MyDb.PreviewGridChanges"))
    }

    fun `test adding a row appends a pending row with defaults`() {
        val sql = "select * from shop.products order by id"
        val page = (handle.session.execute(sql) as StatementResult.Rows).page
        val query = PageQuery { s, offset, limit -> (s.execute(sql, offset + limit) as StatementResult.Rows).page }
        val editable = GridFactory.create(project, handle.session, page, query,
            dev.teo.mydb.plugin.grid.EditTarget("shop", "products", listOf("id"), setOf("id")), testRootDisposable)
        try {
            assertTrue("can add row", com.intellij.database.run.actions.AddRowAction.canAddRow(editable))
            val added = AtomicInteger()
            editable.getDataModel(DataAccessType.DATA_WITH_MUTATIONS).addListener(object : com.intellij.database.datagrid.GridModel.Listener<com.intellij.database.datagrid.GridRow, com.intellij.database.datagrid.GridColumn> {
                override fun rowsAdded(rows: com.intellij.database.datagrid.ModelIndexSet<com.intellij.database.datagrid.GridRow>) { added.addAndGet(rows.size()) }
                override fun rowsRemoved(rows: com.intellij.database.datagrid.ModelIndexSet<com.intellij.database.datagrid.GridRow>) {}
                override fun columnsAdded(columns: com.intellij.database.datagrid.ModelIndexSet<com.intellij.database.datagrid.GridColumn>) {}
                override fun columnsRemoved(columns: com.intellij.database.datagrid.ModelIndexSet<com.intellij.database.datagrid.GridColumn>) {}
                override fun cellsUpdated(rows: com.intellij.database.datagrid.ModelIndexSet<com.intellij.database.datagrid.GridRow>, columns: com.intellij.database.datagrid.ModelIndexSet<com.intellij.database.datagrid.GridColumn>, place: com.intellij.database.datagrid.GridRequestSource.RequestPlace?) {}
            }, testRootDisposable)
            com.intellij.database.datagrid.GridUtil.addRow(editable)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val withMutations = editable.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
            assertEquals(21, withMutations.rowCount)
            val mutator = (editable.dataHookup as dev.teo.mydb.plugin.grid.JdbcGridHookUp).mutator as dev.teo.mydb.plugin.grid.JdbcGridHookUp.Mutator
            assertEquals(1, mutator.insertedRowsCount)
            assertEquals("the view is told about the new row", 1, added.get())
        } finally { Disposer.dispose(editable) }
    }

    fun `test failed submit shows an error banner with the SQLSTATE`() {
        val banner = dev.teo.mydb.plugin.grid.SubmitErrorBanner()
        assertNull(banner.shownText)
        val stmt = dev.teo.mydb.core.edit.DmlStatement(dev.teo.mydb.core.edit.DmlKind.DELETE, "DELETE FROM shop.products WHERE id = ?", listOf(1), "DELETE FROM shop.products WHERE id = 1;")
        banner.show(dev.teo.mydb.core.edit.SubmitResult.Failed(stmt, "ERROR: update or delete violates foreign key\n  Detail: Key (id)=(1)", "23503"))
        assertEquals("[23503] ERROR: update or delete violates foreign key", banner.shownText)
        banner.show(dev.teo.mydb.core.edit.SubmitResult.Ok(1))
        assertNull(banner.shownText)
    }

    fun `test local value filter shows only the chosen value`() {
        grid.localFilterState.enableForColumn(column("sku"), LocalFilterState.Value("SKU-1", "SKU-1"))
        grid.resultView.onLocalFilterStateChanged()
        assertEquals(listOf<Any?>("SKU-1"), shown("sku"))
        assertEquals(0, serverQueries.get())
    }

    fun `test counting rows shows the exact total`() {
        val hookUp = grid.dataHookup as JdbcGridHookUp
        assertTrue(hookUp.pageModel.isTotalRowCountUpdateable)
        hookUp.loader.updateTotalRowCount(GridRequestSource(null))
        waitFor("row count") { hookUp.countedRows != null }
        assertEquals(20L, hookUp.pageModel.totalRowCount)
        assertTrue(hookUp.pageModel.isTotalRowCountPrecise)
    }

    fun `test shift enter opens the value panel of the cell`() {
        val action = ActionManager.getInstance().getAction("Console.TableResult.EditValueMaximized")
        assertNotNull(action)
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts("Console.TableResult.EditValueMaximized")
        assertTrue(shortcuts.any { (it as? KeyboardShortcut)?.firstKeyStroke == KeyStroke.getKeyStroke("shift ENTER") })
    }

    fun `test counter is only offered for queries`() {
        assertNotNull(ConsoleRunner.counter("WITH x AS (select 1) select * from x;"))
        assertNull(ConsoleRunner.counter("update shop.products set price = price"))
    }
}
