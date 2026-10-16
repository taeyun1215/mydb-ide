package dev.teo.mydb.plugin.compare

import com.intellij.openapi.project.Project
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.diff.DiffItem
import dev.teo.mydb.core.diff.DiffStatus
import dev.teo.mydb.core.diff.SchemaDiff
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.core.model.DbSchema
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.plugin.console.OutputLog
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService

/** A line of the comparison tree: a kind folder or a compared object. */
sealed interface CompareRow {
    val depth: Int
    data class Folder(val label: String, val members: List<DiffItem>, override val depth: Int) : CompareRow
    data class Item(val item: DiffItem, override val depth: Int) : CompareRow
}

/** One row of the object properties diff. */
data class PropertyRow(val name: String, val source: String?, val target: String?) { val differs get() = source != target }

private val KIND_ORDER = listOf("table", "view", "column", "key", "foreign key", "index", "check")
private fun folderLabel(kind: String) = when (kind) { "index" -> "indexes"; "foreign key" -> "foreign keys"; else -> kind + "s" }

/** One side of a comparison: a schema, or a single table of it. */
data class CompareSide(val config: DataSourceConfig, val database: String, val schema: String, val table: String? = null) {
    val title get() = "${config.name}: $database.$schema" + (table?.let { ".$it" } ?: "")
}

/**
 * State of the migration dialog: the diff of two introspected sides, checked items,
 * direction and the generated script. Comparison uses the in-memory model only.
 */
class SchemaCompare(private val project: Project, source: CompareSide, target: CompareSide) {
    var source = source; private set
    var target = target; private set
    lateinit var diff: SchemaDiff; private set
    private val unchecked = mutableSetOf<String>()

    init { recompute() }

    private fun schemaOf(side: CompareSide): DbSchema =
        ModelService.getInstance(project).model(side.config.id)?.schema(side.database, side.schema)
            ?: error("${side.title} is not introspected yet")

    /** The script is written for the target's DBMS. */
    private val dialect get() = dev.teo.mydb.core.exec.SqlDialect.of(target.config.dbms)

    private fun recompute() {
        val src = schemaOf(source)
        val tgt = schemaOf(target)
        diff = if (source.table != null && target.table != null) {
            // table ↔ table: compare the two tables under the target's name so they are matched
            val s = src.table(source.table!!) ?: error("${source.title} not found")
            val t = tgt.table(target.table!!) ?: error("${target.title} not found")
            SchemaDiff(src.copy(tables = listOf(s.copy(name = t.name))), tgt.copy(tables = listOf(t)), { n -> if (n == t.name) s else src.table(n) }, dialect)
        } else SchemaDiff(src, tgt, dialect = dialect)
        unchecked.retainAll(diff.items.flatMap { it.flatten() }.map { it.id }.toSet())
    }

    /** Swaps source and target and regenerates everything. */
    fun swap() {
        source = target.also { target = source }
        unchecked.clear()
        recompute()
    }

    fun rows(showIdentical: Boolean): List<Pair<DiffItem, Int>> {
        fun walk(item: DiffItem, depth: Int): List<Pair<DiffItem, Int>> =
            if (!showIdentical && item.status == DiffStatus.SAME && item.children.none { it.status != DiffStatus.SAME }) emptyList()
            else listOf(item to depth) + item.children.flatMap { walk(it, depth + 1) }
        return diff.items.flatMap { walk(it, 0) }
    }

    /** Items grouped into kind folders: `tables`, `views`, then per table `columns`, `keys`, …. */
    fun tree(showIdentical: Boolean): List<CompareRow> {
        fun visible(i: DiffItem) = showIdentical || i.status != DiffStatus.SAME || i.children.any { it.status != DiffStatus.SAME }
        fun group(items: List<DiffItem>, depth: Int): List<CompareRow> = items.filter(::visible)
            .groupBy { it.kind }.toList().sortedBy { (kind, _) -> KIND_ORDER.indexOf(kind).let { if (it < 0) KIND_ORDER.size else it } }
            .flatMap { (kind, members) ->
                listOf<CompareRow>(CompareRow.Folder(folderLabel(kind), members, depth)) +
                    members.flatMap { m -> listOf<CompareRow>(CompareRow.Item(m, depth + 1)) + group(m.children, depth + 2) }
            }
        return group(diff.items, 0)
    }

    fun isChecked(row: CompareRow) = when (row) {
        is CompareRow.Item -> isChecked(row.item)
        is CompareRow.Folder -> row.members.all(::isChecked)
    }
    fun setChecked(row: CompareRow, checked: Boolean) = when (row) {
        is CompareRow.Item -> setChecked(row.item, checked)
        is CompareRow.Folder -> row.members.forEach { setChecked(it, checked) }
    }

    /**
     * Property / source / target rows for the "Object Properties Diff" tab; properties
     * that differ come first.
     */
    fun properties(item: DiffItem): List<PropertyRow> {
        val tableName = item.id.removePrefix("table:").substringBefore('/')
        val src = schemaOf(source).table(source.table ?: tableName)
        val tgt = schemaOf(target).table(tableName)
        val child = item.id.substringAfter('/', "").takeIf { it.isNotEmpty() }
        val (kind, name) = child?.split(':', limit = 2)?.let { it[0] to it[1] } ?: ("table" to tableName)
        fun props(t: DbTable?): Map<String, String?> = if (t == null) emptyMap() else when (kind) {
            "column" -> t.column(name)?.let { c -> linkedMapOf("Type" to c.type, "Not null" to c.notNull.toString(), "Default" to c.default?.takeUnless { c.generated },
                "Generated" to c.default?.takeIf { c.generated }, "Identity" to c.identity?.name?.lowercase(), "Comment" to c.comment) } ?: emptyMap()
            "key" -> (listOfNotNull(t.primaryKey) + t.uniqueKeys).firstOrNull { it.name == name }?.let { k ->
                linkedMapOf("Kind" to if (k.primary) "primary key" else "unique", "Columns" to k.columns.joinToString(", ")) } ?: emptyMap()
            "fk" -> t.foreignKeys.firstOrNull { it.name == name }?.let { f -> linkedMapOf("Columns" to f.columns.joinToString(", "),
                "References" to "${f.refTable} (${f.refColumns.joinToString(", ")})", "On delete" to f.onDelete.sql, "On update" to f.onUpdate.sql) } ?: emptyMap()
            "index" -> t.indexes.firstOrNull { it.name == name }?.let { i -> linkedMapOf("Columns" to i.columns.joinToString(", "),
                "Unique" to i.unique.toString(), "Method" to i.method, "Where" to i.predicate) } ?: emptyMap()
            "check" -> t.checks.firstOrNull { it.name == name }?.let { c -> linkedMapOf("Expression" to c.expression) } ?: emptyMap()
            else -> linkedMapOf("Kind" to t.kind.name.lowercase().replace('_', ' '), "Columns" to t.columns.size.toString(),
                "Primary key" to t.primaryKey?.columns?.joinToString(", "), "Unique keys" to t.uniqueKeys.size.toString(),
                "Foreign keys" to t.foreignKeys.size.toString(), "Indexes" to t.indexes.count { !it.backsConstraint }.toString(),
                "Checks" to t.checks.size.toString(), "Comment" to t.comment, "Owner" to t.owner, "Definition" to t.viewDefinition?.trim())
        }
        val l = props(src); val r = props(tgt)
        return (l.keys + r.keys).distinct().map { PropertyRow(it, l[it], r[it]) }
            .filter { it.source != null || it.target != null }
            .sortedBy { if (it.differs) 0 else 1 }
    }

    fun isChecked(item: DiffItem) = item.id !in unchecked
    fun setChecked(item: DiffItem, checked: Boolean) {
        val ids = item.flatten().map { it.id }
        if (checked) unchecked.removeAll(ids.toSet()) else unchecked.addAll(ids)
    }
    fun setAllChecked(checked: Boolean) { if (checked) unchecked.clear() else unchecked.addAll(diff.items.flatMap { it.flatten() }.map { it.id }) }

    /** Script for the checked differences. */
    fun script(): String = diff.script(diff.items.flatMap { it.flatten() }.map { it.id }.filter { it !in unchecked }.toSet())

    /** DDL of the selected item's table on each side, for the DDL diff tab. */
    fun ddl(item: DiffItem): Pair<String, String> {
        val tableName = item.id.removePrefix("table:").substringBefore('/')
        fun side(s: CompareSide, name: String?): String {
            val schema = schemaOf(s)
            val t: DbTable = name?.let(schema::table) ?: return ""
            return dev.teo.mydb.plugin.util.Dialects.ddl(project, s.config, s.database, s.schema, t)
        }
        val srcName = if (source.table != null) source.table else tableName
        return side(source, srcName) to side(target, tableName)
    }

    /** The script in a new console of the target data source, not executed. */
    fun openInConsole(): com.intellij.openapi.vfs.VirtualFile {
        val text = script()
        val file = dev.teo.mydb.plugin.console.ConsoleService.getInstance(project).openConsole(target.config, newConsole = true)
        dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project).bind(file, target.config.id, target.schema)
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.setText(text) }
        return file
    }

    /** Number of changed line blocks between the two DDLs of [item]. */
    fun ddlDifferences(item: DiffItem): Int {
        val (left, right) = ddl(item)
        return com.intellij.diff.comparison.ComparisonManager.getInstance()
            .compareLines(left, right, com.intellij.diff.comparison.ComparisonPolicy.DEFAULT, com.intellij.openapi.progress.DumbProgressIndicator.INSTANCE).size
    }

    /**
     * Runs the script on the target data source, stopping at the first error, then introspects the
     * target schema again. Blocking: call off the EDT. Returns the error, if any.
     */
    fun execute(): String? {
        val config = target.config
        val password = DataSourceStorage.getInstance(project).password(config.id)
        val log = OutputLog.getInstance(project)
        val title = "migration ${target.schema}"
        var error: String? = null
        Session(dev.teo.mydb.plugin.datasource.DbConnector.get(project).open(config, password, SessionPurpose.CONSOLE)).use { s ->
            for (st in StatementSplitter.split(script())) {
                log.statement(title, "${target.database}.${target.schema}", st.text)
                when (val r = s.execute(st.text)) {
                    is StatementResult.Error -> { error = "[${r.sqlState ?: ""}] ${r.message}"; log.error(title, error!!); break }
                    else -> {}
                }
            }
        }
        ModelService.getInstance(project).refreshNow(config, RefreshTarget.Schema(target.database, target.schema))
        recompute()
        return error
    }
}
