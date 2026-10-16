package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.JdbcUrls
import dev.teo.mydb.core.model.*
import java.sql.Connection

/**
 * SQLite metadata: one database named after the file with the schema `main`; tables and
 * views from `sqlite_master`, details from the table pragmas. Internal `sqlite_` tables are hidden.
 */
class SqliteIntrospector(private val connector: Connector = Connector()) : Introspector {

    override fun introspect(config: DataSourceConfig, password: String?, previous: DbModel?, target: RefreshTarget): DbModel {
        val file = JdbcUrls.parse(config.dbms, config.url)?.database.orEmpty()
        val dbName = file.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "main" }
        connector.open(config, password, SessionPurpose.INTROSPECTION).use { c ->
            val version = c.metaData.databaseProductVersion
            val schema = DbSchema("main", isDefault = true, tables = readTables(c))
            return DbModel(config.id, version, dbName, listOf(DbDatabase(dbName, true, listOf("main"), listOf(schema))))
        }
    }

    override fun listSchemas(config: DataSourceConfig, password: String?, database: String): List<String> = listOf("main")

    private fun <T> Connection.rows(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    private fun q(name: String) = "'" + name.replace("'", "''") + "'"

    private fun readTables(c: Connection): List<DbTable> {
        data class Entry(val type: String, val name: String, val sql: String?)
        val entries = c.rows("select type, name, sql from sqlite_master where type in ('table', 'view') and name not like 'sqlite_%' order by name") {
            Entry(it.getString(1), it.getString(2), it.getString(3))
        }
        val indexSql = c.rows("select tbl_name, sql from sqlite_master where type = 'index' and sql is not null") { it.getString(1) to it.getString(2) }
            .groupBy({ it.first }, { it.second })
        return entries.mapIndexed { i, e ->
            data class Col(val cid: Int, val name: String, val type: String, val notNull: Boolean, val default: String?, val pk: Int)
            val cols = c.rows("pragma table_info(${q(e.name)})") {
                Col(it.getInt("cid"), it.getString("name"), it.getString("type").orEmpty(), it.getInt("notnull") == 1, it.getString("dflt_value"), it.getInt("pk"))
            }
            val pkCols = cols.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }
            val isView = e.type == "view"
            val fks = if (isView) emptyList() else c.rows("pragma foreign_key_list(${q(e.name)})") {
                listOf(it.getInt("id").toString(), it.getString("table"), it.getString("from"), it.getString("to").orEmpty(), it.getString("on_delete"), it.getString("on_update"))
            }.groupBy { it[0] }.values.map { group ->
                DbForeignKey(
                    name = "${e.name}_${group.first()[1]}_fk_${group.first()[0]}", columns = group.map { it[2] }, refSchema = "main",
                    refTable = group.first()[1], refColumns = group.map { it[3] }.filter { it.isNotEmpty() },
                    onDelete = fkAction(group.first()[4]), onUpdate = fkAction(group.first()[5]),
                )
            }
            data class Idx(val name: String, val unique: Boolean, val origin: String, val partial: Boolean)
            val idx = if (isView) emptyList() else c.rows("pragma index_list(${q(e.name)})") {
                Idx(it.getString("name"), it.getInt("unique") == 1, it.getString("origin"), it.getInt("partial") == 1)
            }
            fun idxCols(name: String) = c.rows("pragma index_info(${q(name)})") { it.getString("name") }.map { it ?: "<expression>" }
            val uniques = idx.filter { it.origin == "u" }.map { DbKey(it.name, idxCols(it.name), primary = false) }
            val pkIndex = idx.firstOrNull { it.origin == "pk" }
            val indexes = idx.map { DbIndex(it.name, idxCols(it.name), it.unique, "btree", backsConstraint = it.origin != "c", predicate = null) }
            DbTable(
                oid = (i + 1).toLong(), name = e.name, kind = if (isView) RelationKind.VIEW else RelationKind.TABLE,
                columns = cols.map { DbColumn(it.name, it.cid + 1, it.type.lowercase(), it.notNull, it.default) },
                primaryKey = pkCols.takeIf { it.isNotEmpty() }?.let { DbKey(pkIndex?.name ?: "${e.name}_pk", it, primary = true) },
                uniqueKeys = uniques,
                foreignKeys = fks,
                indexes = indexes,
                viewDefinition = if (isView) e.sql?.let(::viewBody) else null,
                sourceDdl = (listOfNotNull(e.sql?.trimEnd()?.let { if (it.endsWith(";")) it else "$it;" }) +
                    indexSql[e.name].orEmpty().map { if (it.trimEnd().endsWith(";")) it.trimEnd() else it.trimEnd() + ";" }).joinToString("\n\n"),
            )
        }
    }

    /** Body after the first top-level `AS` of a `create view` statement. */
    private fun viewBody(sql: String): String? = Regex("""(?is)\bas\b(.*)$""").find(sql)?.groupValues?.get(1)?.trim()

    private fun fkAction(s: String?) = when (s?.uppercase()) {
        "CASCADE" -> FkAction.CASCADE
        "RESTRICT" -> FkAction.RESTRICT
        "SET NULL" -> FkAction.SET_NULL
        "SET DEFAULT" -> FkAction.SET_DEFAULT
        else -> FkAction.NO_ACTION
    }
}
