package dev.teo.mydb.core.plan

import dev.teo.mydb.core.exec.Session
import kotlinx.serialization.json.*

/** One node of an execution plan. */
data class PlanNode(
    /** Human-readable operation, e.g. `Full Scan (Seq Scan)`. */
    val operation: String,
    val serverNodeType: String,
    /** Key arguments, e.g. `table: customers;` */
    val parameters: String,
    val rows: Double?,
    val totalCost: Double?,
    val startupCost: Double?,
    val actualRows: Double?,
    val actualTotalTime: Double?,
    val actualStartupTime: Double?,
    /** Every scalar property reported by the server for this node. */
    val properties: Map<String, String>,
    val children: List<PlanNode>,
) {
    val title: String get() = parameters.substringAfter("table: ", "").substringBefore(';').takeIf { it.isNotEmpty() }?.let { "$operation of $it" } ?: operation
    fun flatten(): List<PlanNode> = listOf(this) + children.flatMap { it.flatten() }
}

data class QueryPlan(val root: PlanNode, val analyzed: Boolean)

object QueryPlans {
    /** Runs EXPLAIN; with [analyze] DML is executed inside a transaction that is rolled back. */
    fun explain(session: Session, statement: String, analyze: Boolean): QueryPlan {
        if (session.connection.metaData.databaseProductName == "SQLite") return explainSqlite(session, statement)
        if (dev.teo.mydb.core.exec.SqlDialect.of(session.connection) == dev.teo.mydb.core.exec.SqlDialect.MYSQL) return explainMysql(session, statement)
        val sql = "EXPLAIN (FORMAT JSON${if (analyze) ", ANALYSE" else ""}) $statement"
        val isDml = Regex("^\\s*(insert|update|delete|merge)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)
        val json = if (analyze && isDml) {
            val c = session.connection
            val wasAuto = c.autoCommit
            c.autoCommit = false
            val sp = if (!wasAuto) c.setSavepoint() else null
            try { query(c, sql) } finally {
                if (sp != null) c.rollback(sp) else c.rollback()
                c.autoCommit = wasAuto
            }
        } else query(session.connection, sql)
        return parse(json, statementKind(statement), analyze)
    }

    /**
     * SQLite: `EXPLAIN QUERY PLAN` rows (id, parent, notused, detail) as a tree under the statement node
     * Operations are the first word of the detail (SCAN, SEARCH, USE TEMP B-TREE, …).
     */
    fun explainSqlite(session: Session, statement: String): QueryPlan {
        data class Row(val id: Int, val parent: Int, val detail: String)
        val rows = session.connection.createStatement().use { st ->
            st.executeQuery("EXPLAIN QUERY PLAN $statement").use { rs -> buildList { while (rs.next()) add(Row(rs.getInt(1), rs.getInt(2), rs.getString(4))) } }
        }
        fun node(r: Row): PlanNode {
            val words = r.detail.split(' ')
            val op = words.takeWhile { it.all { ch -> ch.isUpperCase() || ch == '-' } && it.isNotEmpty() }.joinToString(" ").ifEmpty { r.detail }
            val table = Regex("""^(?:SCAN|SEARCH) (\S+)""").find(r.detail)?.groupValues?.get(1)
            return PlanNode(op, op, listOfNotNull(table?.let { "table: $it;" }, r.detail.removePrefix(op).trim().takeIf { it.isNotEmpty() }).joinToString(" "),
                null, null, null, null, null, null, mapOf("id" to r.id.toString(), "parent" to r.parent.toString(), "detail" to r.detail),
                rows.filter { it.parent == r.id }.map(::node))
        }
        val kind = statementKind(statement)
        val root = PlanNode(kind.replaceFirstChar { it.uppercase() }, "Query", "", null, null, null, null, null, null,
            mapOf("Operation" to kind), rows.filter { it.parent == 0 }.map(::node))
        return QueryPlan(root, analyzed = false)
    }

    /**
     * MySQL / MariaDB: the rows of tabular `EXPLAIN` (one per table access, in join order) under the statement
     * node. The access type names the operation.
     */
    fun explainMysql(session: Session, statement: String): QueryPlan {
        val rows = session.connection.createStatement().use { st ->
            st.executeQuery("EXPLAIN $statement").use { rs ->
                val md = rs.metaData
                val names = (1..md.columnCount).map { md.getColumnLabel(it).lowercase() }
                buildList { while (rs.next()) add(names.mapIndexed { i, n -> n to rs.getString(i + 1) }.toMap()) }
            }
        }
        val children = rows.map { r ->
            val type = r["type"]
            val op = when (type) {
                "ALL" -> "Full Scan (ALL)"
                "index" -> "Full Index Scan (index)"
                "range" -> "Range Scan (range)"
                "ref", "eq_ref", "const", "ref_or_null", "system" -> "Index Lookup ($type)"
                null -> r["select_type"] ?: "Step"
                else -> type
            }
            val params = listOfNotNull(r["table"]?.let { "table: $it;" }, r["key"]?.let { "index: $it;" }, r["extra"]?.takeIf { it.isNotBlank() }?.let { "$it;" })
            PlanNode(op, type ?: "", params.joinToString(" "), r["rows"]?.toDoubleOrNull(), null, null, null, null, null,
                r.filterValues { it != null }.mapValues { it.value!! }, emptyList())
        }
        val kind = statementKind(statement)
        val root = PlanNode(kind.replaceFirstChar { it.uppercase() }, "Query", "", null, null, null, null, null, null, mapOf("Operation" to kind), children)
        return QueryPlan(root, analyzed = false)
    }

    fun parse(json: String, statementKind: String, analyzed: Boolean): QueryPlan {
        val top = Json.parseToJsonElement(json).jsonArray.first().jsonObject
        val plan = parseNode(top["Plan"]!!.jsonObject)
        val rootProps = top.filterKeys { it != "Plan" }.mapValues { (_, v) -> scalar(v) }
        val root = PlanNode(
            operation = statementKind.replaceFirstChar { it.uppercase() }, serverNodeType = "Query",
            parameters = "", rows = null, totalCost = null, startupCost = null,
            actualRows = null, actualTotalTime = null, actualStartupTime = null,
            properties = mapOf("Operation" to statementKind) + rootProps, children = listOf(plan),
        )
        return QueryPlan(root, analyzed)
    }

    private fun parseNode(o: JsonObject): PlanNode {
        val type = o["Node Type"]!!.jsonPrimitive.content
        val strategy = o["Strategy"]?.jsonPrimitive?.contentOrNull
        val params = buildList {
            o["Relation Name"]?.let { add("table: ${it.jsonPrimitive.content};") }
            o["Index Name"]?.let { add("index: ${it.jsonPrimitive.content};") }
            o["Hash Cond"]?.let { add("condition: ${it.jsonPrimitive.content};") }
            o["Filter"]?.let { add("filter: ${it.jsonPrimitive.content};") }
            o["Group Key"]?.let { add("group key: ${it.jsonArray.joinToString { e -> e.jsonPrimitive.content }};") }
        }.joinToString(" ")
        return PlanNode(
            operation = displayName(type, strategy), serverNodeType = type, parameters = params,
            rows = o.num("Plan Rows"), totalCost = o.num("Total Cost"), startupCost = o.num("Startup Cost"),
            actualRows = o.num("Actual Rows"), actualTotalTime = o.num("Actual Total Time"), actualStartupTime = o.num("Actual Startup Time"),
            properties = o.filterKeys { it != "Plans" }.mapValues { (_, v) -> scalar(v) },
            children = o["Plans"]?.jsonArray?.map { parseNode(it.jsonObject) } ?: emptyList(),
        )
    }

    /** Server node names rendered with an explanatory prefix. */
    fun displayName(nodeType: String, strategy: String? = null): String = when (nodeType) {
        "Seq Scan" -> "Full Scan (Seq Scan)"
        "Hash" -> "Transformation (Hash)"
        "Sort" -> "Transformation (Sort)"
        "Materialize" -> "Transformation (Materialize)"
        else -> nodeType
    }

    private fun statementKind(sql: String) =
        Regex("^\\s*(\\w+)").find(sql)?.groupValues?.get(1)?.lowercase()?.let { if (it == "with") "select" else it } ?: "query"

    private fun query(c: java.sql.Connection, sql: String): String =
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }

    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

    private fun scalar(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        is JsonArray -> v.joinToString(", ", "[", "]") { scalar(it) }
        is JsonObject -> v.entries.joinToString(", ", "{", "}") { "${it.key}=${scalar(it.value)}" }
    }
}
