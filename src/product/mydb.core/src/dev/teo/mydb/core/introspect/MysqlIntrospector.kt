package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.JdbcUrls
import dev.teo.mydb.core.exec.SqlDialect
import dev.teo.mydb.core.model.*
import java.sql.Connection

/**
 * MySQL / MariaDB metadata from `information_schema`. A MySQL database is a schema: the model
 * has one database named after the server's catalog (`def`) whose schemas are the URL's database and the
 * schemas added to the introspection scope.
 */
class MysqlIntrospector(private val connector: Connector = Connector()) : Introspector {

    override fun introspect(config: DataSourceConfig, password: String?, previous: DbModel?, target: RefreshTarget): DbModel {
        connector.open(config, password, SessionPurpose.INTROSPECTION).use { c ->
            val all = names(c, "select schema_name from information_schema.schemata order by schema_name")
            val defaultSchema = JdbcUrls.parse(config.dbms, config.url)?.database?.takeIf { it.isNotBlank() }
            val chosen = (listOfNotNull(defaultSchema) + config.introspectionScope.schemas.filter { it.database == CATALOG }.map { it.schema })
                .distinct().filter { it in all }
            // no database in the URL and nothing chosen: every user schema, as a server-level data source shows them
            val wanted = chosen.ifEmpty { all.filter { it.lowercase() !in SYSTEM } }
            val schemas = wanted.map { s -> DbSchema(s, isDefault = s == defaultSchema, tables = readTables(c, s)) }
            val db = DbDatabase(CATALOG, true, all, schemas)
            return DbModel(config.id, "${c.metaData.databaseProductName} ${c.metaData.databaseProductVersion}", CATALOG, listOf(db))
        }
    }

    override fun listSchemas(config: DataSourceConfig, password: String?, database: String): List<String> =
        connector.open(config, password, SessionPurpose.INTROSPECTION).use { c -> names(c, "select schema_name from information_schema.schemata order by schema_name") }

    private fun names(c: Connection, sql: String) = c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun <T> Connection.rows(sql: String, schema: String, map: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps -> ps.setString(1, schema); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    private fun readTables(c: Connection, schema: String): List<DbTable> {
        data class T(val name: String, val type: String, val comment: String?)
        val tables = c.rows("select table_name, table_type, table_comment from information_schema.tables where table_schema = ? order by table_name", schema) {
            T(it.getString(1), it.getString(2), it.getString(3)?.takeIf { s -> s.isNotEmpty() })
        }
        val columns = c.rows("""
            select table_name, column_name, ordinal_position, column_type, is_nullable, column_default, extra, column_comment
            from information_schema.columns where table_schema = ? order by table_name, ordinal_position
        """.trimIndent(), schema) {
            val extra = it.getString(7).orEmpty().lowercase()
            it.getString(1) to DbColumn(
                name = it.getString(2), position = it.getInt(3), type = it.getString(4), notNull = it.getString(5) == "NO",
                default = it.getString(6), identity = if ("auto_increment" in extra) IdentityKind.BY_DEFAULT else null,
                comment = it.getString(8)?.takeIf { s -> s.isNotEmpty() },
                generated = "generated" in extra && "default_generated" !in extra,
            )
        }.groupBy({ it.first }, { it.second })
        data class K(val table: String, val name: String, val type: String, val column: String, val pos: Int, val refSchema: String?, val refTable: String?, val refColumn: String?)
        val keys = c.rows("""
            select tc.table_name, tc.constraint_name, tc.constraint_type, k.column_name, k.ordinal_position,
                   k.referenced_table_schema, k.referenced_table_name, k.referenced_column_name
            from information_schema.table_constraints tc
                 join information_schema.key_column_usage k
                      on k.constraint_schema = tc.constraint_schema and k.constraint_name = tc.constraint_name and k.table_name = tc.table_name
            where tc.table_schema = ? and tc.constraint_type in ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
            order by tc.table_name, tc.constraint_name, k.ordinal_position
        """.trimIndent(), schema) { K(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6), it.getString(7), it.getString(8)) }
            .groupBy { it.table }
        val rules = c.rows("select constraint_name, table_name, update_rule, delete_rule from information_schema.referential_constraints where constraint_schema = ?", schema) {
            (it.getString(2) to it.getString(1)) to (it.getString(3) to it.getString(4))
        }.toMap()
        data class I(val table: String, val name: String, val unique: Boolean, val column: String?, val seq: Int, val type: String)
        val stats = c.rows("""
            select table_name, index_name, non_unique, column_name, seq_in_index, index_type
            from information_schema.statistics where table_schema = ? order by table_name, index_name, seq_in_index
        """.trimIndent(), schema) { I(it.getString(1), it.getString(2), it.getInt(3) == 0, it.getString(4), it.getInt(5), it.getString(6)) }
            .groupBy { it.table }
        val views = c.rows("select table_name, view_definition from information_schema.views where table_schema = ?", schema) { it.getString(1) to it.getString(2) }.toMap()
        val d = SqlDialect.MYSQL
        return tables.mapIndexed { i, t ->
            val isView = t.type.contains("VIEW")
            val tk = keys[t.name].orEmpty()
            val constraints = tk.groupBy { it.name }
            val pk = constraints.values.firstOrNull { it.first().type == "PRIMARY KEY" }?.let { g -> DbKey("PRIMARY", g.sortedBy { it.pos }.map { it.column }, true) }
            val uks = constraints.values.filter { it.first().type == "UNIQUE" }.map { g -> DbKey(g.first().name, g.sortedBy { it.pos }.map { it.column }, false) }
            val fks = constraints.values.filter { it.first().type == "FOREIGN KEY" }.map { g ->
                val rule = rules[t.name to g.first().name]
                DbForeignKey(g.first().name, g.sortedBy { it.pos }.map { it.column }, g.first().refSchema ?: schema, g.first().refTable.orEmpty(),
                    g.sortedBy { it.pos }.mapNotNull { it.refColumn }, fkAction(rule?.second), fkAction(rule?.first))
            }
            val keyNames = constraints.keys + "PRIMARY"
            val indexes = stats[t.name].orEmpty().groupBy { it.name }.map { (name, cols) ->
                DbIndex(name, cols.sortedBy { it.seq }.map { it.column ?: "<expression>" }, cols.first().unique, cols.first().type.lowercase(), backsConstraint = name in keyNames)
            }
            val show = runCatching {
                c.createStatement().use { st -> st.executeQuery("show create ${if (isView) "view" else "table"} ${d.qualified(schema, t.name)}").use { rs -> if (rs.next()) rs.getString(2) else null } }
            }.getOrNull()
            DbTable(
                oid = (i + 1).toLong(), name = t.name, kind = if (isView) RelationKind.VIEW else RelationKind.TABLE,
                columns = columns[t.name].orEmpty(), primaryKey = pk, uniqueKeys = uks, foreignKeys = fks, indexes = indexes,
                comment = t.comment.takeUnless { isView }, viewDefinition = views[t.name], sourceDdl = show?.let { if (it.endsWith(";")) it else "$it;" },
            )
        }
    }

    private fun fkAction(s: String?) = when (s?.uppercase()) {
        "CASCADE" -> FkAction.CASCADE
        "RESTRICT" -> FkAction.RESTRICT
        "SET NULL" -> FkAction.SET_NULL
        "SET DEFAULT" -> FkAction.SET_DEFAULT
        else -> FkAction.NO_ACTION
    }

    companion object {
        const val CATALOG = "def"
        val SYSTEM = setOf("information_schema", "mysql", "performance_schema", "sys")
    }
}
