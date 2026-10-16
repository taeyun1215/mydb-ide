package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.JdbcUrls
import dev.teo.mydb.core.model.*
import java.sql.Connection
import java.sql.ResultSet

/** What a refresh should re-read. */
sealed interface RefreshTarget {
    /** Whole data source: schema lists plus every in-scope schema (incrementally). */
    data object All : RefreshTarget
    data class Schema(val database: String, val schema: String) : RefreshTarget
    data class Tables(val database: String, val schema: String, val tables: Set<String>) : RefreshTarget
}

/** Counters of the last run; used to verify incremental behaviour. */
data class IntrospectionStats(var relationsRead: Int = 0, var schemasRead: Int = 0, var connectionsOpened: Int = 0)

/**
 * Reads PostgreSQL catalogs into a [DbModel]. Every run uses its own connection that is
 * closed afterwards, so it never blocks console sessions.
 */
class PostgresIntrospector(private val connector: Connector = Connector()) : Introspector {
    val lastStats = IntrospectionStats()

    override fun introspect(
        config: DataSourceConfig,
        password: String?,
        previous: DbModel?,
        target: RefreshTarget,
    ): DbModel {
        lastStats.relationsRead = 0; lastStats.schemasRead = 0; lastStats.connectionsOpened = 0
        val defaultDb = JdbcUrls.parse(config.dbms, config.url)?.database ?: "postgres"
        val (serverVersion, databaseNames) = withConnection(config, password, defaultDb) { c ->
            c.metaData.databaseProductVersion to c.strings("select datname from pg_database where not datistemplate and datallowconn order by datname")
        }
        val scope = config.introspectionScope
        val databases = databaseNames.mapNotNull { dbName ->
            val isDefault = dbName == defaultDb
            val wanted = scope.schemas.filter { it.database == dbName }.map { it.schema }.toSet()
            val prevDb = previous?.database(dbName)
            if (!isDefault && wanted.isEmpty()) {
                return@mapNotNull DbDatabase(dbName, false, prevDb?.allSchemaNames ?: emptyList(), emptyList())
            }
            if (target !is RefreshTarget.All && targetDatabase(target) != dbName && prevDb != null) return@mapNotNull prevDb
            withConnection(config, password, dbName) { c -> readDatabase(c, dbName, isDefault, scope.includeDefaultSchema && isDefault, wanted, prevDb, target) }
        }
        return DbModel(config.id, serverVersion, defaultDb, databases)
    }

    /** Schema names on the server for the scope chooser, including system schemas. */
    override fun listSchemas(config: DataSourceConfig, password: String?, database: String): List<String> =
        withConnection(config, password, database) { c -> c.strings("select nspname from pg_namespace where nspname not like 'pg_toast%' and nspname not like 'pg_temp%' order by nspname") }

    private fun targetDatabase(t: RefreshTarget) = when (t) {
        is RefreshTarget.Schema -> t.database
        is RefreshTarget.Tables -> t.database
        RefreshTarget.All -> null
    }

    private fun <T> withConnection(config: DataSourceConfig, password: String?, database: String, block: (Connection) -> T): T {
        val parts = JdbcUrls.parse(config.dbms, config.url)
        val url = if (parts != null) JdbcUrls.build(config.dbms, parts.host, parts.port ?: config.dbms.defaultPort, database) else config.url
        lastStats.connectionsOpened++
        return connector.open(config.copy(url = url), password, SessionPurpose.INTROSPECTION).use { c ->
            c.isReadOnly = true
            block(c)
        }
    }

    private fun readDatabase(
        c: Connection, dbName: String, isDefault: Boolean, includeDefaultSchema: Boolean,
        wanted: Set<String>, prev: DbDatabase?, target: RefreshTarget,
    ): DbDatabase {
        val allSchemas = c.strings("select nspname from pg_namespace where nspname not like 'pg_toast%' and nspname not like 'pg_temp%' order by nspname")
        val defaultSchema = c.strings("select (current_schemas(false))[1]").firstOrNull()
        val inScope = buildSet {
            if (includeDefaultSchema && defaultSchema != null) add(defaultSchema)
            addAll(wanted.filter { it in allSchemas })
        }
        val schemas = allSchemas.filter { it in inScope }.map { name ->
            val prevSchema = prev?.schema(name)
            when {
                target is RefreshTarget.Tables && target.schema == name && prevSchema != null ->
                    refreshTables(c, prevSchema, target.tables)
                target is RefreshTarget.Tables && prevSchema != null -> prevSchema
                target is RefreshTarget.Schema && target.schema != name && prevSchema != null -> prevSchema
                else -> readSchema(c, name, name == defaultSchema, prevSchema)
            }
        }
        return DbDatabase(dbName, isDefault, allSchemas, schemas)
    }

    // ---- schema level ------------------------------------------------------------------------

    private fun readSchema(c: Connection, name: String, isDefault: Boolean, prev: DbSchema?): DbSchema {
        lastStats.schemasRead++
        val snapshot = c.longs("select pg_snapshot_xmin(pg_current_snapshot())::text::bigint").first()
        val nsOid = c.longs("select oid from pg_namespace where nspname = ?", name).first()
        val owner = c.strings("select pg_get_userbyid(nspowner) from pg_namespace where oid = ?", nsOid).firstOrNull()

        val tables: List<DbTable> = if (prev == null || prev.stateTxid == 0L) {
            readRelations(c, nsOid, null)
        } else {
            val existing = c.longs("select oid from pg_class where relnamespace = ? and relkind in ('r','v','m','f','p')", nsOid).toSet()
            val changed = changedRelations(c, nsOid, prev.stateTxid)
            val keep = prev.tables.filter { it.oid in existing && it.oid !in changed }
            val newOids = existing - keep.map { it.oid }.toSet()
            (keep + if (newOids.isEmpty()) emptyList() else readRelations(c, nsOid, newOids)).sortedBy { it.name }
        }
        return DbSchema(
            name = name, isDefault = isDefault, owner = owner, tables = tables,
            sequences = readSequences(c, nsOid), routines = readRoutines(c, nsOid), stateTxid = snapshot,
        )
    }

    private fun refreshTables(c: Connection, prev: DbSchema, names: Set<String>): DbSchema {
        val nsOid = c.longs("select oid from pg_namespace where nspname = ?", prev.name).first()
        val oids = c.longs("select oid from pg_class where relnamespace = ? and relname = any(?)", nsOid, names.toTypedArray()).toSet()
        val fresh = if (oids.isEmpty()) emptyList() else readRelations(c, nsOid, oids)
        val merged = prev.tables.filter { it.name !in names } + fresh
        return prev.copy(tables = merged.sortedBy { it.name })
    }

    /**
     * Relations whose own catalog rows or dependent rows (columns, defaults, constraints, indexes,
     * comments) were written after [sinceXid]. `age` is wraparound-safe.
     */
    private fun changedRelations(c: Connection, nsOid: Long, sinceXid: Long): Set<Long> {
        val xid = (sinceXid % 4294967296L).toString()
        return c.longs(
            """
            select c.oid from pg_class c
            where c.relnamespace = ? and c.relkind in ('r','v','m','f','p') and (
                  age(c.xmin) <= age(?::xid)
               or exists (select 1 from pg_attribute a where a.attrelid = c.oid and age(a.xmin) <= age(?::xid))
               or exists (select 1 from pg_attrdef d where d.adrelid = c.oid and age(d.xmin) <= age(?::xid))
               or exists (select 1 from pg_constraint k where k.conrelid = c.oid and age(k.xmin) <= age(?::xid))
               or exists (select 1 from pg_index i where i.indrelid = c.oid and age(i.xmin) <= age(?::xid))
               or exists (select 1 from pg_description d where d.objoid = c.oid and age(d.xmin) <= age(?::xid)))
            """.trimIndent(),
            nsOid, xid, xid, xid, xid, xid, xid,
        ).toSet()
    }

    // ---- relations ---------------------------------------------------------------------------

    private data class Rel(val oid: Long, val name: String, val kind: RelationKind, val owner: String?, val comment: String?)

    private fun readRelations(c: Connection, nsOid: Long, only: Set<Long>?): List<DbTable> {
        val filter = if (only != null) " and c.oid = any(?)" else ""
        val params = if (only != null) arrayOf<Any>(nsOid, only.toTypedArray()) else arrayOf<Any>(nsOid)
        val rels = c.query(
            """
            select c.oid, c.relname, c.relkind, pg_get_userbyid(c.relowner), obj_description(c.oid, 'pg_class')
            from pg_class c where c.relnamespace = ? and c.relkind in ('r','v','m','f','p')$filter
            order by c.relname
            """.trimIndent(), *params,
        ) { rs -> Rel(rs.getLong(1), rs.getString(2), relKind(rs.getString(3)), rs.getString(4), rs.getString(5)) }
        lastStats.relationsRead += rels.size
        if (rels.isEmpty()) return emptyList()
        val oids = rels.map { it.oid }.toTypedArray()

        val columns = c.query(
            """
            select a.attrelid, a.attname, a.attnum, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid), a.attidentity, col_description(a.attrelid, a.attnum), a.attgenerated
            from pg_attribute a left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
            where a.attrelid = any(?) and a.attnum > 0 and not a.attisdropped
            order by a.attrelid, a.attnum
            """.trimIndent(), oids,
        ) { rs ->
            rs.getLong(1) to DbColumn(
                name = rs.getString(2), position = rs.getInt(3), type = rs.getString(4), notNull = rs.getBoolean(5),
                default = rs.getString(6),
                identity = when (rs.getString(7)) { "a" -> IdentityKind.ALWAYS; "d" -> IdentityKind.BY_DEFAULT; else -> null },
                comment = rs.getString(8),
                generated = rs.getString(9) == "s",
            )
        }.groupBy({ it.first }, { it.second })

        data class Con(val rel: Long, val name: String, val type: String, val keys: List<Int>, val fkeys: List<Int>,
                       val refSchema: String?, val refTable: String?, val refOid: Long, val del: String?, val upd: String?,
                       val def: String, val deferrable: Boolean)
        val constraints = c.query(
            """
            select k.conrelid, k.conname, k.contype, k.conkey, k.confkey, fn.nspname, fc.relname, k.confrelid,
                   k.confdeltype, k.confupdtype, pg_get_constraintdef(k.oid, false), k.condeferrable
            from pg_constraint k
                 left join pg_class fc on fc.oid = k.confrelid
                 left join pg_namespace fn on fn.oid = fc.relnamespace
            where k.conrelid = any(?)
            order by k.conrelid, k.conname
            """.trimIndent(), oids,
        ) { rs ->
            Con(rs.getLong(1), rs.getString(2), rs.getString(3), rs.intArray(4), rs.intArray(5), rs.getString(6), rs.getString(7),
                rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getBoolean(12))
        }
        // referenced column names may live in other tables
        val refAttnames: Map<Pair<Long, Int>, String> = constraints.filter { it.type == "f" }.map { it.refOid }.distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { refs ->
                c.query("select attrelid, attnum, attname from pg_attribute where attrelid = any(?) and attnum > 0", refs.toTypedArray()) { rs ->
                    (rs.getLong(1) to rs.getInt(2)) to rs.getString(3)
                }.toMap()
            } ?: emptyMap()

        data class Idx(val rel: Long, val name: String, val unique: Boolean, val method: String, val cols: List<String>, val backs: Boolean, val pred: String?)
        val indexes = c.query(
            """
            select i.indrelid, ic.relname, i.indisunique, am.amname,
                   array(select pg_get_indexdef(i.indexrelid, n, true) from generate_series(1, i.indnkeyatts) n),
                   exists(select 1 from pg_constraint k where k.conindid = i.indexrelid and k.contype in ('p','u','x')),
                   pg_get_expr(i.indpred, i.indrelid, true)
            from pg_index i join pg_class ic on ic.oid = i.indexrelid join pg_am am on am.oid = ic.relam
            where i.indrelid = any(?)
            order by i.indrelid, ic.relname
            """.trimIndent(), oids,
        ) { rs -> Idx(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.stringArray(5), rs.getBoolean(6), rs.getString(7)) }
            .groupBy { it.rel }

        val viewDefs = rels.filter { it.kind == RelationKind.VIEW || it.kind == RelationKind.MATERIALIZED_VIEW }
            .associate { r -> r.oid to c.strings("select pg_get_viewdef(?::oid, true)", r.oid).firstOrNull() }
        val viewRefs: Map<Long, List<String>> = viewDefs.keys.takeIf { it.isNotEmpty() }?.let { views ->
            c.query(
                """
                select distinct r.ev_class, tn.nspname || '.' || t.relname
                from pg_rewrite r
                     join pg_depend d on d.classid = 'pg_rewrite'::regclass and d.objid = r.oid and d.refclassid = 'pg_class'::regclass
                     join pg_class t on t.oid = d.refobjid and t.oid <> r.ev_class
                     join pg_namespace tn on tn.oid = t.relnamespace
                where r.ev_class = any(?)
                order by 1, 2
                """.trimIndent(), views.toTypedArray(),
            ) { rs -> rs.getLong(1) to rs.getString(2) }.groupBy({ it.first }, { it.second })
        } ?: emptyMap()

        return rels.map { r ->
            val cols = columns[r.oid].orEmpty()
            val byNum = cols.associateBy({ it.position }, { it.name })
            val cons = constraints.filter { it.rel == r.oid }
            DbTable(
                oid = r.oid, name = r.name, kind = r.kind, columns = cols,
                primaryKey = cons.firstOrNull { it.type == "p" }?.let { DbKey(it.name, it.keys.mapNotNull(byNum::get), true, it.deferrable) },
                uniqueKeys = cons.filter { it.type == "u" }.map { DbKey(it.name, it.keys.mapNotNull(byNum::get), false, it.deferrable) },
                foreignKeys = cons.filter { it.type == "f" }.map { k ->
                    DbForeignKey(
                        name = k.name, columns = k.keys.mapNotNull(byNum::get),
                        refSchema = k.refSchema!!, refTable = k.refTable!!,
                        refColumns = k.fkeys.mapNotNull { refAttnames[k.refOid to it] },
                        onDelete = fkAction(k.del), onUpdate = fkAction(k.upd),
                    )
                },
                checks = cons.filter { it.type == "c" }.map { DbCheck(it.name, checkExpression(it.def)) },
                indexes = indexes[r.oid].orEmpty().map { DbIndex(it.name, it.cols, it.unique, it.method, it.backs, it.pred) },
                comment = r.comment, owner = r.owner, viewDefinition = viewDefs[r.oid], viewReferences = viewRefs[r.oid].orEmpty(),
            )
        }
    }

    private fun readSequences(c: Connection, nsOid: Long): List<DbSequence> = c.query(
        """
        select s.relname, t.relname, a.attname
        from pg_class s
             left join pg_depend d on d.objid = s.oid and d.classid = 'pg_class'::regclass and d.refclassid = 'pg_class'::regclass and d.deptype in ('a','i')
             left join pg_class t on t.oid = d.refobjid
             left join pg_attribute a on a.attrelid = d.refobjid and a.attnum = d.refobjsubid
        where s.relnamespace = ? and s.relkind = 'S'
        order by s.relname
        """.trimIndent(), nsOid,
    ) { rs -> DbSequence(rs.getString(1), rs.getString(2), rs.getString(3)) }

    private fun readRoutines(c: Connection, nsOid: Long): List<DbRoutine> = c.query(
        """
        select p.proname, p.prokind, pg_get_function_arguments(p.oid), pg_get_function_result(p.oid),
               case when p.prokind in ('f','p') then pg_get_functiondef(p.oid) end
        from pg_proc p where p.pronamespace = ?
        order by p.proname
        """.trimIndent(), nsOid,
    ) { rs ->
        DbRoutine(rs.getString(1), when (rs.getString(2)) { "p" -> RoutineKind.PROCEDURE; "a" -> RoutineKind.AGGREGATE; else -> RoutineKind.FUNCTION },
            rs.getString(3), rs.getString(4), rs.getString(5))
    }

    private fun relKind(k: String) = when (k) {
        "v" -> RelationKind.VIEW
        "m" -> RelationKind.MATERIALIZED_VIEW
        "f" -> RelationKind.FOREIGN_TABLE
        "p" -> RelationKind.PARTITIONED_TABLE
        else -> RelationKind.TABLE
    }

    private fun fkAction(a: String?) = when (a) {
        "r" -> FkAction.RESTRICT; "c" -> FkAction.CASCADE; "n" -> FkAction.SET_NULL; "d" -> FkAction.SET_DEFAULT
        else -> FkAction.NO_ACTION
    }
}

// ---- small JDBC helpers ----------------------------------------------------------------------

internal fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p ->
            when (p) {
                is Array<*> -> st.setArray(i + 1, createArrayOf(if (p.firstOrNull() is String) "text" else "bigint", p))
                else -> st.setObject(i + 1, p)
            }
        }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

internal fun Connection.strings(sql: String, vararg params: Any?) = query(sql, *params) { it.getString(1) }
internal fun Connection.longs(sql: String, vararg params: Any?) = query(sql, *params) { it.getLong(1) }

private fun ResultSet.intArray(i: Int): List<Int> =
    (getArray(i)?.array as? Array<*>)?.map { (it as Number).toInt() } ?: emptyList()

private fun ResultSet.stringArray(i: Int): List<String> =
    (getArray(i)?.array as? Array<*>)?.map { it.toString() } ?: emptyList()

/**
 * `CHECK ((price >= (0)::numeric))` → `(price >= (0)::numeric)`: the server's non-pretty text with the
 * redundant outer pair removed, which is the readable form.
 */
internal fun checkExpression(def: String): String {
    val e = def.removePrefix("CHECK ").trim()
    if (!(e.startsWith("((") && e.endsWith("))"))) return e
    val inner = e.substring(1, e.length - 1)
    var depth = 0
    for ((i, c) in inner.withIndex()) {
        if (c == '(') depth++ else if (c == ')') depth--
        if (depth == 0 && i < inner.length - 1) return e // the outer pair of inner closes early: keep e
    }
    return inner
}
