package dev.teo.mydb.core.diagram

import dev.teo.mydb.core.model.DbSchema
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.core.plan.PlanNode

/** Generic diagram used by the ERD and the plan diagram. */
data class DiagramNode(
    val id: String,
    val title: String,
    val kind: String,
    /** All rows of the node card: (icon kind, name, detail); [rows] are the ones shown. */
    val allRows: List<Triple<String, String, String>>,
    /** Properties shown in the panel under the diagram for the selected node. */
    val properties: Map<String, String> = emptyMap(),
) {
    var rows: List<Triple<String, String, String>> = allRows
    var x = 0.0; var y = 0.0
    val width get() = 40.0 + 7.0 * (listOf(title.length) + rows.map { it.second.length + it.third.length + 2 }).max()
    val height get() = 28.0 + 22.0 * rows.size
}

/** Directed edge [from] → [to] (child → parent for foreign keys, view → table it reads for [EdgeKind.VIEW]). */
data class DiagramEdge(val from: String, val to: String, val label: String, val kind: EdgeKind = EdgeKind.FOREIGN_KEY)

enum class EdgeKind { FOREIGN_KEY, VIEW, VIRTUAL }

/** What ERD cards show: all columns, key columns only or none; types; view lines. */
data class ErdOptions(val columns: ColumnMode = ColumnMode.ALL, val types: Boolean = true, val viewEdges: Boolean = true) {
    enum class ColumnMode { ALL, KEYS, NONE }
}

class Diagram(val nodes: List<DiagramNode>, val edges: List<DiagramEdge>) {
    fun node(id: String) = nodes.first { it.id == id }

    var options = ErdOptions()
        private set
    val visibleEdges: List<DiagramEdge> get() = edges.filter { options.viewEdges || it.kind != EdgeKind.VIEW }

    /** Applies display options to the cards; positions are kept. */
    fun apply(o: ErdOptions) {
        options = o
        for (n in nodes) {
            n.rows = n.allRows
                .filter { (icon, _, _) -> o.columns == ErdOptions.ColumnMode.ALL || (o.columns == ErdOptions.ColumnMode.KEYS && icon != "column") }
                .map { if (o.types) it else it.copy(third = "") }
        }
    }
}

object Diagrams {
    /**
     * ERD of a schema: one card per table/view, regular columns before key columns,
     * an edge per foreign key labelled `<fk column>:<referenced column>`.
     */
    fun erd(schema: DbSchema): Diagram {
        val nodes = schema.tables.map { t ->
            val pk = t.primaryKey?.columns.orEmpty().toSet()
            val fkCols = t.foreignKeys.flatMap { it.columns }.toSet()
            val ordered = t.columns.filter { it.name !in pk } + t.columns.filter { it.name in pk }
            DiagramNode(t.name, t.name, if (t.isView) "view" else "table", ordered.map { c ->
                val icon = when { c.name in pk && c.name in fkCols -> "pk-fk"; c.name in pk -> "pk"; c.name in fkCols -> "fk"; else -> "column" }
                Triple(icon, c.name, PlanTypes.short(c.type))
            })
        }
        val names = nodes.map { it.id }.toSet()
        val edges = schema.tables.flatMap { t ->
            t.foreignKeys.filter { it.refTable in names && it.refSchema == schema.name }.map { fk ->
                DiagramEdge(t.name, fk.refTable, fk.columns.zip(fk.refColumns).joinToString(", ") { (a, b) -> "$a:$b" },
                    if (fk.virtual) EdgeKind.VIRTUAL else EdgeKind.FOREIGN_KEY)
            } + t.viewReferences.mapNotNull { q ->
                val (s, n) = q.substringBefore('.') to q.substringAfter('.')
                DiagramEdge(t.name, n, "", EdgeKind.VIEW).takeIf { s == schema.name && n in names }
            }
        }
        return Diagram(nodes, edges)
    }

    /** Plan diagram: root on the left, scans on the right. Edges point from child to parent. */
    fun plan(root: PlanNode): Diagram {
        val nodes = mutableListOf<DiagramNode>()
        val edges = mutableListOf<DiagramEdge>()
        fun walk(n: PlanNode, path: String) {
            val rows = buildList {
                (n.actualRows ?: n.rows)?.let { add(Triple("rows", "Rows", fmt(it))) }
                if (n.startupCost != null && n.totalCost != null) add(Triple("cost", "Cost", "${fmt(n.startupCost)}..${fmt(n.totalCost)}"))
            }
            nodes += DiagramNode(path, n.title, "plan", rows, n.properties)
            n.children.forEachIndexed { i, c -> val cp = "$path.$i"; walk(c, cp); edges += DiagramEdge(cp, path, "") }
        }
        walk(root, "0")
        return Diagram(nodes, edges)
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    fun erdOf(tables: List<DbTable>, schemaName: String) = erd(DbSchema(schemaName, false, tables = tables))

    /** ERD of one table with the tables it references and the tables referencing it (same schema). */
    fun neighbourhood(schema: DbSchema, table: String): Diagram {
        val t = schema.table(table) ?: return erdOf(emptyList(), schema.name)
        val parents = t.foreignKeys.filter { it.refSchema == schema.name }.map { it.refTable }.toSet()
        val children = schema.tables.filter { o -> o.foreignKeys.any { it.refSchema == schema.name && it.refTable == table } }.map { it.name }.toSet()
        return erdOf(schema.tables.filter { it.name == table || it.name in parents || it.name in children }, schema.name)
    }
}

internal object PlanTypes {
    fun short(type: String) = type.replace("character varying", "varchar").replace(Regex("^numeric\\((\\d+),(\\d+)\\)"), "numeric($1,$2)")
}
