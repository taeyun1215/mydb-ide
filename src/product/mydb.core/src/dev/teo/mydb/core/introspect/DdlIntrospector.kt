package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.JdbcUrls
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.core.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * Model of a DDL data source: `create table / view / index / schema`, `alter table … add constraint`,
 * `comment on` and `owner to` statements of SQL files, PostgreSQL syntax. Other statements are skipped.
 */
class DdlIntrospector : Introspector {
    override fun introspect(config: DataSourceConfig, password: String?, previous: DbModel?, target: RefreshTarget): DbModel =
        parse(config.id, files(JdbcUrls.parse(config.dbms, config.url)?.database.orEmpty()).joinToString("\n;\n") { Files.readString(it) })

    override fun listSchemas(config: DataSourceConfig, password: String?, database: String): List<String> =
        introspect(config, password).databases.flatMap { it.schemas.map { s -> s.name } }

    /** The file, or the `.sql` files of the folder (recursively) by path. */
    fun files(path: String): List<Path> {
        val p = Path.of(path)
        return when {
            path.isBlank() || !Files.exists(p) -> emptyList()
            p.isDirectory() -> Files.walk(p).use { s -> s.filter { Files.isRegularFile(it) && it.extension.lowercase() == "sql" }.sorted().toList() }
            else -> listOf(p)
        }
    }

    private class MutableTable(val name: String, var kind: RelationKind) {
        val columns = mutableListOf<DbColumn>()
        var pk: DbKey? = null
        val uniques = mutableListOf<DbKey>()
        val fks = mutableListOf<DbForeignKey>()
        val indexes = mutableListOf<DbIndex>()
        val checks = mutableListOf<DbCheck>()
        var comment: String? = null
        var owner: String? = null
        var viewDefinition: String? = null
    }

    private val schemas = LinkedHashMap<String, LinkedHashMap<String, MutableTable>>()
    private fun table(schema: String, name: String, kind: RelationKind = RelationKind.TABLE) =
        schemas.getOrPut(schema) { LinkedHashMap() }.getOrPut(name) { MutableTable(name, kind) }

    fun parse(dataSourceId: String, text: String): DbModel {
        schemas.clear()
        for (st in StatementSplitter.split(text)) runCatching { statement(stripComments(st.text).trim()) }
        // `references t` without columns means t's primary key, as the server resolves it
        for ((_, tables) in schemas) for (t in tables.values) {
            t.fks.replaceAll { f -> if (f.refColumns.isNotEmpty()) f else f.copy(refColumns = schemas[f.refSchema]?.get(f.refTable)?.pk?.columns ?: emptyList()) }
        }
        val list = schemas.entries.map { (s, tables) ->
            DbSchema(s, isDefault = s == "public", tables = tables.values.mapIndexed { i, t ->
                DbTable((i + 1).toLong(), t.name, t.kind, t.columns.toList(), t.pk, t.uniqueKeys(), t.fks.toList(), t.indexes.toList(), t.checks.toList(),
                    t.comment, t.owner, t.viewDefinition)
            })
        }.ifEmpty { listOf(DbSchema("public", true)) }
        return DbModel(dataSourceId, "DDL", "ddl", listOf(DbDatabase("ddl", true, list.map { it.name }, list)))
    }

    private fun MutableTable.uniqueKeys() = uniques.toList()

    private fun stripComments(s: String) = s.lines().joinToString("\n") { line ->
        var inQuote = false; var i = 0
        while (i < line.length) {
            if (line[i] == '\'') inQuote = !inQuote
            if (!inQuote && line.startsWith("--", i)) return@joinToString line.substring(0, i)
            i++
        }
        line
    }

    // ---- statements -----------------------------------------------------------------------------

    private val CREATE_TABLE = Regex("""(?is)^create\s+(?:(?:global|local)\s+)?(?:temp(?:orary)?\s+|unlogged\s+)?table\s+(?:if\s+not\s+exists\s+)?(\S+?)\s*\((.*)\)[^)]*$""")
    private val CREATE_VIEW = Regex("""(?is)^create\s+(?:or\s+replace\s+)?(materialized\s+)?view\s+(?:if\s+not\s+exists\s+)?([^\s(]+)\s*(\([^)]*\))?\s+as\s+(.*)$""")
    private val CREATE_INDEX = Regex("""(?is)^create\s+(unique\s+)?index\s+(?:concurrently\s+)?(?:if\s+not\s+exists\s+)?(\S+)\s+on\s+(?:only\s+)?(\S+?)\s*(?:using\s+(\w+)\s*)?\((.*?)\)\s*(?:where\s+(.*))?$""")
    private val CREATE_SCHEMA = Regex("""(?is)^create\s+schema\s+(?:if\s+not\s+exists\s+)?(\S+).*$""")
    private val ALTER_ADD = Regex("""(?is)^alter\s+table\s+(?:only\s+)?(?:if\s+exists\s+)?(\S+)\s+add\s+(constraint\s+.*|primary\s+key.*|unique.*|foreign\s+key.*|check.*)$""")
    private val ALTER_OWNER = Regex("""(?is)^alter\s+(?:table|view|materialized\s+view)\s+(\S+)\s+owner\s+to\s+(\S+)$""")
    private val COMMENT_TABLE = Regex("""(?is)^comment\s+on\s+(?:table|view)\s+(\S+)\s+is\s+'(.*)'$""")
    private val COMMENT_COLUMN = Regex("""(?is)^comment\s+on\s+column\s+(\S+)\s+is\s+'(.*)'$""")

    private fun statement(s: String) {
        CREATE_TABLE.find(s)?.let { m -> createTable(m.groupValues[1], m.groupValues[2]); return }
        CREATE_VIEW.find(s)?.let { m ->
            val (schema, name) = qualified(m.groupValues[2])
            val v = table(schema, name, if (m.groupValues[1].isNotBlank()) RelationKind.MATERIALIZED_VIEW else RelationKind.VIEW)
            v.viewDefinition = m.groupValues[4].trim()
            m.groupValues[3].takeIf { it.isNotBlank() }?.let { cols ->
                splitTop(cols.trim().removePrefix("(").removeSuffix(")")).forEachIndexed { i, c -> v.columns += DbColumn(ident(c), i + 1, "", false) }
            }
            return
        }
        CREATE_INDEX.find(s)?.let { m ->
            val (schema, tableName) = qualified(m.groupValues[3])
            val (_, indexName) = qualified(m.groupValues[2])
            table(schema, tableName).indexes += DbIndex(indexName, splitTop(m.groupValues[5]).map { identOrExpr(it) }, m.groupValues[1].isNotBlank(),
                m.groupValues[4].ifBlank { "btree" }.lowercase(), predicate = m.groupValues[6].trim().ifEmpty { null })
            return
        }
        CREATE_SCHEMA.find(s)?.let { m -> schemas.getOrPut(ident(m.groupValues[1])) { LinkedHashMap() }; return }
        ALTER_ADD.find(s)?.let { m -> val (schema, name) = qualified(m.groupValues[1]); tableConstraint(schema, table(schema, name), m.groupValues[2]); return }
        ALTER_OWNER.find(s)?.let { m -> val (schema, name) = qualified(m.groupValues[1]); table(schema, name).owner = ident(m.groupValues[2]); return }
        COMMENT_TABLE.find(s)?.let { m -> val (schema, name) = qualified(m.groupValues[1]); table(schema, name).comment = m.groupValues[2].replace("''", "'"); return }
        COMMENT_COLUMN.find(s)?.let { m ->
            val parts = m.groupValues[1].split('.').map(::ident)
            val (schema, t, c) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple("public", parts[0], parts[1])
            val tab = table(schema, t)
            tab.columns.indexOfFirst { it.name == c }.takeIf { it >= 0 }?.let { tab.columns[it] = tab.columns[it].copy(comment = m.groupValues[2].replace("''", "'")) }
        }
    }

    private fun createTable(rawName: String, body: String) {
        val (schema, name) = qualified(rawName)
        val t = table(schema, name)
        for (element in splitTop(body)) {
            val e = element.trim()
            if (Regex("""(?i)^(constraint|primary\s+key|unique|foreign\s+key|check|exclude)\b""").containsMatchIn(e)) tableConstraint(schema, t, e)
            else column(schema, t, e)
        }
    }

    private val COLUMN_STOP = Regex("""(?i)\b(constraint|not\s+null|null|default|primary\s+key|unique|references|check|generated|collate)\b""")

    private fun column(schema: String, t: MutableTable, e: String) {
        val nameEnd = if (e.startsWith("\"")) e.indexOf('"', 1) + 1 else e.indexOfFirst { it.isWhitespace() }.let { if (it < 0) e.length else it }
        val name = ident(e.substring(0, nameEnd))
        val rest = e.substring(nameEnd).trim()
        val stop = firstTopLevel(rest, COLUMN_STOP)
        var type = (if (stop < 0) rest else rest.substring(0, stop)).trim().replace(Regex("\\s+"), " ").lowercase()
        val tail = if (stop < 0) "" else rest.substring(stop)
        var default = Regex("""(?is)\bdefault\s+(.+?)(?=\s+(?:not\s+null|null|primary\s+key|unique|references|check|constraint|generated)\b|$)""").find(tail)?.groupValues?.get(1)?.trim()
        var identity: IdentityKind? = null
        when {
            Regex("""(?i)generated\s+always\s+as\s+identity""").containsMatchIn(tail) -> identity = IdentityKind.ALWAYS
            Regex("""(?i)generated\s+by\s+default\s+as\s+identity""").containsMatchIn(tail) -> identity = IdentityKind.BY_DEFAULT
        }
        // serial types are integer columns fed by a sequence, as the server stores them
        when (type) {
            "serial", "serial4" -> { type = "integer"; default = "nextval('${schema}.${t.name}_${name}_seq'::regclass)" }
            "bigserial", "serial8" -> { type = "bigint"; default = "nextval('${schema}.${t.name}_${name}_seq'::regclass)" }
            "smallserial", "serial2" -> { type = "smallint"; default = "nextval('${schema}.${t.name}_${name}_seq'::regclass)" }
            else -> type = canonicalType(type)
        }
        val generated = Regex("""(?is)generated\s+always\s+as\s+\((.*)\)\s+stored""").find(tail)?.groupValues?.get(1)
        val pkInline = Regex("""(?i)\bprimary\s+key\b""").containsMatchIn(tail)
        val notNull = Regex("""(?i)\bnot\s+null\b""").containsMatchIn(tail) || pkInline
        t.columns += DbColumn(name, t.columns.size + 1, type, notNull, generated ?: default, identity, generated = generated != null)
        val constraintName = Regex("""(?i)\bconstraint\s+(\S+)""").find(tail)?.groupValues?.get(1)?.let(::ident)
        if (pkInline) t.pk = DbKey(constraintName ?: "${t.name}_pkey", listOf(name), true)
        if (Regex("""(?i)\bunique\b""").containsMatchIn(tail)) t.uniques += DbKey(constraintName ?: "${t.name}_${name}_key", listOf(name), false)
        Regex("""(?i)\bcheck\s*\(""").find(tail)?.let { m ->
            balanced(tail, m.range.last)?.let { expr -> t.checks += DbCheck(constraintName ?: "${t.name}_${name}_check", expr) }
        }
        Regex("""(?is)\breferences\s+([^\s(]+)\s*(?:\(([^)]*)\))?(.*)$""").find(tail)?.let { m ->
            val (rs, rt) = qualified(m.groupValues[1], schema)
            t.fks += DbForeignKey(constraintName ?: "${t.name}_${name}_fkey", listOf(name), rs, rt,
                m.groupValues[2].takeIf { it.isNotBlank() }?.let { splitTop(it).map(::ident) } ?: emptyList(), action(m.groupValues[3], "delete"), action(m.groupValues[3], "update"))
        }
    }

    /** Type names as the server prints them (`format_type`), so DDL and database compare equal. */
    private fun canonicalType(t: String): String {
        val base = t.substringBefore('(').trim()
        val args = t.substringAfter('(', "").substringBeforeLast(')', "").replace(Regex("\\s*,\\s*"), ",").trim()
        val withArgs = { name: String -> if (args.isEmpty()) name else "$name($args)" }
        return when (base) {
            "int", "int4" -> "integer"
            "int8" -> "bigint"
            "int2" -> "smallint"
            "bool" -> "boolean"
            "float8" -> "double precision"
            "float4" -> "real"
            "varchar", "character varying" -> withArgs("character varying")
            "char", "character", "bpchar" -> withArgs("character").let { if (args.isEmpty()) "character(1)" else it }
            "decimal", "numeric" -> withArgs("numeric")
            "timestamptz", "timestamp with time zone" -> "timestamp with time zone"
            "timestamp", "timestamp without time zone" -> "timestamp without time zone"
            "timetz", "time with time zone" -> "time with time zone"
            "time", "time without time zone" -> "time without time zone"
            else -> withArgs(base)
        }
    }

    private fun tableConstraint(schema: String, t: MutableTable, e: String) {
        val named = Regex("""(?is)^constraint\s+(\S+)\s+(.*)$""").find(e)
        val name = named?.groupValues?.get(1)?.let(::ident)
        val body = named?.groupValues?.get(2) ?: e
        fun cols(s: String) = splitTop(s).map(::ident)
        Regex("""(?is)^primary\s+key\s*\((.*?)\)""").find(body)?.let { t.pk = DbKey(name ?: "${t.name}_pkey", cols(it.groupValues[1]), true); return }
        Regex("""(?is)^unique\s*\((.*?)\)""").find(body)?.let { m -> val c = cols(m.groupValues[1]); t.uniques += DbKey(name ?: "${t.name}_${c.joinToString("_")}_key", c, false); return }
        Regex("""(?is)^foreign\s+key\s*\((.*?)\)\s*references\s+([^\s(]+)\s*(?:\((.*?)\))?(.*)$""").find(body)?.let { m ->
            val c = cols(m.groupValues[1]); val (rs, rt) = qualified(m.groupValues[2], schema)
            t.fks += DbForeignKey(name ?: "${t.name}_${c.joinToString("_")}_fkey", c, rs, rt, m.groupValues[3].takeIf { it.isNotBlank() }?.let(::cols) ?: emptyList(),
                action(m.groupValues[4], "delete"), action(m.groupValues[4], "update"))
            return
        }
        Regex("""(?is)^check\s*(\(.*\))""").find(body)?.let { t.checks += DbCheck(name ?: "${t.name}_check", it.groupValues[1].trim()) }
    }

    private fun action(s: String, on: String): FkAction = Regex("""(?i)on\s+$on\s+(no\s+action|restrict|cascade|set\s+null|set\s+default)""").find(s)?.groupValues?.get(1)
        ?.lowercase()?.replace(Regex("\\s+"), " ")?.let { a -> FkAction.entries.firstOrNull { it.sql == a } } ?: FkAction.NO_ACTION

    // ---- lexical helpers ------------------------------------------------------------------------

    private fun ident(s: String): String = s.trim().let { if (it.startsWith("\"")) it.removeSurrounding("\"").replace("\"\"", "\"") else it.lowercase() }
    private fun identOrExpr(s: String) = s.trim().let { if (Regex("""^("[^"]+"|[A-Za-z_][\w$]*)$""").matches(it)) ident(it) else it }

    private fun qualified(s: String, defaultSchema: String = "public"): Pair<String, String> {
        val parts = splitDots(s.trim())
        return if (parts.size >= 2) ident(parts[parts.size - 2]) to ident(parts.last()) else defaultSchema to ident(parts.single())
    }

    private fun splitDots(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false
        for (ch in s) { if (ch == '"') q = !q; if (ch == '.' && !q) { out += cur.toString(); cur.clear() } else cur.append(ch) }
        out += cur.toString(); return out
    }

    /** The parenthesized text starting at [open] (a `(`), including the parentheses. */
    private fun balanced(s: String, open: Int): String? {
        var depth = 0; var sq = false
        for (i in open until s.length) {
            val ch = s[i]
            if (ch == '\'') sq = !sq
            if (sq) continue
            if (ch == '(') depth++
            if (ch == ')') { depth--; if (depth == 0) return s.substring(open, i + 1) }
        }
        return null
    }

    /** Splits at commas outside parentheses and quotes. */
    private fun splitTop(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var depth = 0; var sq = false; var dq = false
        for (ch in s) {
            when {
                ch == '\'' && !dq -> sq = !sq
                ch == '"' && !sq -> dq = !dq
                !sq && !dq && ch == '(' -> depth++
                !sq && !dq && ch == ')' -> depth--
            }
            if (ch == ',' && depth == 0 && !sq && !dq) { out += cur.toString().trim(); cur.clear() } else cur.append(ch)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /** Index of the first match of [re] outside parentheses and quotes, or -1. */
    private fun firstTopLevel(s: String, re: Regex): Int {
        var depth = 0; var sq = false
        for (m in re.findAll(s)) {
            depth = 0; sq = false
            for (i in 0 until m.range.first) { val ch = s[i]; if (ch == '\'') sq = !sq; if (!sq && ch == '(') depth++; if (!sq && ch == ')') depth-- }
            if (depth == 0 && !sq) return m.range.first
        }
        return -1
    }
}
