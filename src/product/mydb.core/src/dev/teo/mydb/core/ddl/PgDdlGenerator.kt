package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.model.*

enum class SchemaQualification { AUTO, ALWAYS, NEVER }
enum class ConstraintPlacement { INLINE, TABLE }

/** Options of the SQL generator. */
data class DdlOptions(
    val qualify: SchemaQualification = SchemaQualification.AUTO,
    val constraintPlacement: ConstraintPlacement = ConstraintPlacement.INLINE,
    val createOrReplace: Boolean = false,
    val ifNotExists: Boolean = false,
    val skipOwner: Boolean = false,
    /** Schema the generated script is "in"; AUTO qualification omits it for objects of this schema. */
    val contextSchema: String? = null,
    /** Rename the schema of generated objects and same-schema references (used by migrations). */
    val targetSchema: String? = null,
    /** `comment on …` statements after the objects. */
    val comments: Boolean = true,
    /** Secondary `create index` statements after tables. */
    val indexes: Boolean = true,
)

/** What the SQL generator writes. */
enum class GenerationMode(val title: String) { CREATE("Create"), DROP("Drop"), DROP_AND_CREATE("Drop and create") }

/**
 * PostgreSQL DDL text generation.
 * Formatting: aligned column list, single-column constraints inline,
 * auto-named constraints without a `constraint` clause, `alter … owner to` after each object.
 */
class PgDdlGenerator(
    private val options: DdlOptions = DdlOptions(),
    /** Resolves referenced tables, so FK target columns can be omitted when they are the target's key. */
    private val lookup: (schema: String, table: String) -> DbTable? = { _, _ -> null },
) {

    fun schemaScript(schema: DbSchema): String = buildList {
        add("create schema ${quote(outSchema(schema.name))};")
        if (!options.skipOwner && schema.owner != null) add("alter schema ${quote(outSchema(schema.name))} owner to ${quote(schema.owner)};")
        schema.sequences.filter { it.ownedByTable == null }.forEach { add("create sequence ${name(schema.name, it.name)};") }
        orderByDependencies(schema.tables.filter { !it.isView }).forEach { add(table(schema.name, it)) }
        schema.tables.filter { it.isView }.forEach { add(view(schema.name, it)) }
    }.joinToString("\n\n")

    /** `drop` statements for [tables]: views first, then tables in reverse dependency order. */
    fun drops(schemaName: String, tables: List<DbTable>, ifExists: Boolean = true, cascade: Boolean = false): String {
        val ie = if (ifExists) "if exists " else ""
        val tail = if (cascade) " cascade;" else ";"
        val views = tables.filter { it.isView }.map {
            "drop ${if (it.kind == RelationKind.MATERIALIZED_VIEW) "materialized view" else "view"} $ie${name(schemaName, it.name)}$tail"
        }
        val plain = orderByDependencies(tables.filter { !it.isView }).reversed().map { "drop table $ie${name(schemaName, it.name)}$tail" }
        return (views + plain).joinToString("\n")
    }

    /** `create table …` followed by indexes, comments and owner. */
    fun table(schemaName: String, t: DbTable): String {
        val parts = mutableListOf(createTable(schemaName, t))
        if (!options.skipOwner && t.owner != null) parts += "alter table ${name(schemaName, t.name)}\n    owner to ${quote(t.owner)};"
        if (options.comments) {
            t.comment?.let { parts += "comment on table ${name(schemaName, t.name)} is ${literal(it)};" }
            t.columns.filter { it.comment != null }.forEach {
                parts += "comment on column ${name(schemaName, t.name)}.${quote(it.name)} is ${literal(it.comment!!)};"
            }
        }
        if (options.indexes) t.indexes.filter { !it.backsConstraint }.forEach { parts += index(schemaName, t.name, it) }
        return parts.joinToString("\n\n")
    }

    fun createTable(schemaName: String, table: DbTable): String {
        val t = table.copy(foreignKeys = table.foreignKeys.filter { !it.virtual })
        val inline = options.constraintPlacement == ConstraintPlacement.INLINE
        val singlePk = t.primaryKey?.takeIf { inline && it.columns.size == 1 }
        val singleUks = t.uniqueKeys.filter { inline && it.columns.size == 1 }.associateBy { it.columns.single() }
        val singleFks = t.foreignKeys.filter { inline && it.columns.size == 1 }.groupBy { it.columns.single() }

        val heads = t.columns.map { c -> ColumnHead(c.name, columnType(t, c), defaultClause(t, c), c.notNull && !(isSerial(t, c) && singlePk?.columns == listOf(c.name))) }
        // three aligned columns after the name: type, `default …`, `not null`
        val nameWidth = heads.maxOfOrNull { quote(it.name).length } ?: 0
        val typeWidth = heads.filter { it.notNull || it.default != null }.maxOfOrNull { it.type.length } ?: 0
        val defaultWidth = heads.filter { it.notNull && it.default != null }.maxOfOrNull { "default ${it.default}".length } ?: 0

        val entries = mutableListOf<String>()
        for ((c, h) in t.columns.zip(heads)) {
            val sb = StringBuilder("    ").append(quote(c.name).padEnd(nameWidth)).append(' ')
            val defaultPart = h.default?.let { "default $it" }
            when {
                h.notNull -> {
                    sb.append(h.type.padEnd(typeWidth))
                    if (defaultWidth > 0) sb.append(' ').append((defaultPart ?: "").padEnd(defaultWidth))
                    sb.append(" not null")
                }
                defaultPart != null -> sb.append(h.type.padEnd(typeWidth)).append(' ').append(defaultPart)
                else -> sb.append(h.type)
            }
            val trailing = sb.toString().trimEnd()
            val lines = mutableListOf(trailing)
            if (singlePk?.columns == listOf(c.name)) lines += constraintLines(singlePk.name, defaultPkName(t), "primary key")
            singleUks[c.name]?.let { lines += constraintLines(it.name, defaultKeyName(t, it.columns), "unique") }
            singleFks[c.name]?.forEach { fk ->
                val clauses = mutableListOf("references ${refTarget(schemaName, fk)}")
                if (fk.onDelete != FkAction.NO_ACTION) clauses += "    on delete ${fk.onDelete.sql}"
                if (fk.onUpdate != FkAction.NO_ACTION) clauses += "    on update ${fk.onUpdate.sql}"
                lines += constraintLines(fk.name, defaultFkName(t, fk.columns), *clauses.toTypedArray())
            }
            entries += lines.joinToString("\n")
        }
        if (t.primaryKey != null && singlePk == null) {
            entries += "    " + named(t.primaryKey.name, defaultPkName(t), "primary key (${t.primaryKey.columns.joinToString(", ") { quote(it) }})")
        }
        t.uniqueKeys.filter { it.columns.size > 1 || !inline }.forEach {
            entries += "    " + named(it.name, defaultKeyName(t, it.columns), "unique (${it.columns.joinToString(", ") { c -> quote(c) }})")
        }
        t.foreignKeys.filter { it.columns.size > 1 || !inline }.forEach { fk ->
            var s = "    " + named(fk.name, defaultFkName(t, fk.columns),
                "foreign key (${fk.columns.joinToString(", ") { quote(it) }}) references ${refTarget(schemaName, fk, alwaysColumns = true)}")
            if (fk.onDelete != FkAction.NO_ACTION) s += "\n        on delete ${fk.onDelete.sql}"
            if (fk.onUpdate != FkAction.NO_ACTION) s += "\n        on update ${fk.onUpdate.sql}"
            entries += s
        }
        t.checks.forEach { entries += "    " + named(it.name, null, "check ${parenthesized(it.expression)}") }

        val create = if (options.ifNotExists) "create table if not exists" else "create table"
        return "$create ${name(schemaName, t.name)}\n(\n${entries.joinToString(",\n")}\n);"
    }

    fun view(schemaName: String, v: DbTable): String {
        val kw = when {
            v.kind == RelationKind.MATERIALIZED_VIEW -> "create materialized view"
            options.createOrReplace -> "create or replace view"
            else -> "create view"
        }
        val cols = v.columns.joinToString(", ") { quote(it.name) }
        val body = v.viewDefinition?.trimEnd()?.removeSuffix(";")?.trimStart() ?: "select null"
        val parts = mutableListOf("$kw ${name(schemaName, v.name)}($cols) as\n$body;")
        if (!options.skipOwner && v.owner != null) parts += "alter ${if (v.kind == RelationKind.MATERIALIZED_VIEW) "materialized view" else "table"} ${name(schemaName, v.name)}\n    owner to ${quote(v.owner)};"
        return parts.joinToString("\n\n")
    }

    fun index(schemaName: String, table: String, i: DbIndex): String {
        val using = if (i.method != "btree") " using ${i.method}" else ""
        val where = i.predicate?.let { "\n    where $it" } ?: ""
        return "create ${if (i.unique) "unique " else ""}index ${quote(i.name)}\n    on ${name(schemaName, table)}$using (${i.columns.joinToString(", ")})$where;"
    }

    // ---- names & types ----------------------------------------------------------------------

    fun name(schemaName: String, objectName: String): String {
        val s = outSchema(schemaName)
        val qualify = when (options.qualify) {
            SchemaQualification.ALWAYS -> true
            SchemaQualification.NEVER -> false
            SchemaQualification.AUTO -> options.contextSchema == null || options.contextSchema != schemaName || options.targetSchema != null
        }
        return if (qualify) "${quote(s)}.${quote(objectName)}" else quote(objectName)
    }

    private fun outSchema(s: String) = options.targetSchema ?: s

    private fun refTarget(schemaName: String, fk: DbForeignKey, alwaysColumns: Boolean = false): String {
        val refSchema = if (fk.refSchema == schemaName) schemaName else fk.refSchema
        val target = if (fk.refSchema == schemaName) name(refSchema, fk.refTable)
                     else "${quote(fk.refSchema)}.${quote(fk.refTable)}"
        val targetKey = lookup(fk.refSchema, fk.refTable)?.primaryKey?.columns
        val showCols = alwaysColumns || targetKey == null || fk.refColumns != targetKey
        return if (showCols) "$target (${fk.refColumns.joinToString(", ") { quote(it) }})" else target
    }

    private data class ColumnHead(val name: String, val type: String, val default: String?, val notNull: Boolean)

    private fun isSerial(t: DbTable, c: DbColumn) =
        c.isSerial && c.type in setOf("integer", "bigint", "smallint") && c.default!!.contains("${t.name}_${c.name}_seq")

    private fun columnType(t: DbTable, c: DbColumn): String {
        if (isSerial(t, c)) return when (c.type) { "bigint" -> "bigserial"; "smallint" -> "smallserial"; else -> "serial" }
        val identity = when (c.identity) {
            IdentityKind.ALWAYS -> " generated always as identity"
            IdentityKind.BY_DEFAULT -> " generated by default as identity"
            null -> ""
        }
        if (c.generated && c.default != null) return normalizeType(c.type) + " generated always as ${parenthesized(c.default)} stored"
        return normalizeType(c.type) + identity
    }

    private fun defaultClause(t: DbTable, c: DbColumn): String? = if (isSerial(t, c) || c.identity != null || c.generated) null else c.default

    private fun constraintLines(actual: String, default: String?, vararg clause: String): List<String> =
        if (actual == default) clause.map { "        $it" }
        else listOf("        constraint ${quote(actual)}") + clause.map { "            $it" }

    private fun named(actual: String, default: String?, clause: String) =
        if (actual == default) clause else "constraint ${quote(actual)}\n        $clause"

    private fun defaultPkName(t: DbTable) = "${t.name}_pkey"
    private fun defaultKeyName(t: DbTable, cols: List<String>) = "${t.name}_${cols.joinToString("_")}_key"
    private fun defaultFkName(t: DbTable, cols: List<String>) = "${t.name}_${cols.joinToString("_")}_fkey"

    private fun literal(s: String) = "'" + s.replace("'", "''") + "'"

    companion object {
        private val RESERVED = setOf(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both", "case", "cast", "check",
            "collate", "column", "constraint", "create", "current_catalog", "current_date", "current_role", "current_time",
            "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end", "except",
            "false", "fetch", "for", "foreign", "from", "grant", "group", "having", "in", "initially", "intersect", "into",
            "lateral", "leading", "limit", "localtime", "localtimestamp", "not", "null", "offset", "on", "only", "or", "order",
            "placing", "primary", "references", "returning", "select", "session_user", "some", "symmetric", "table", "then",
            "to", "trailing", "true", "union", "unique", "user", "using", "variadic", "when", "where", "window", "with",
        )
        private val PLAIN = Regex("[a-z_][a-z0-9_$]*")

        /** `check` needs its expression in parentheses; catalog text already has them, typed text may not. */
        fun parenthesized(expr: String): String {
            val e = expr.trim()
            if (e.startsWith("(") && e.endsWith(")")) {
                var depth = 0
                // the outer pair must enclose everything: `(a) and (b)` is not wrapped
                val wraps = e.withIndex().all { (i, ch) ->
                    if (ch == '(') depth++ else if (ch == ')') depth--
                    depth > 0 || i == e.lastIndex
                }
                if (wraps) return e
            }
            return "($e)"
        }

        fun quote(identifier: String): String =
            if (PLAIN.matches(identifier) && identifier !in RESERVED) identifier else "\"" + identifier.replace("\"", "\"\"") + "\""

        /** Server type names → the short spellings used in generated DDL. */
        fun normalizeType(t: String): String = t
            .replace(Regex("^character varying"), "varchar")
            .replace(Regex("^character\\("), "char(")
            .replace(Regex("^numeric\\((\\d+),(\\d+)\\)"), "numeric($1, $2)")

        /** Tables ordered so that referenced tables come first (cycles keep their input order). */
        fun orderByDependencies(tables: List<DbTable>): List<DbTable> {
            val byName = tables.associateBy { it.name }
            val result = LinkedHashSet<DbTable>()
            val visiting = HashSet<String>()
            fun visit(t: DbTable) {
                if (t in result || !visiting.add(t.name)) return
                t.foreignKeys.mapNotNull { byName[it.refTable] }.filter { it.name != t.name }.forEach(::visit)
                result += t
            }
            tables.forEach(::visit)
            return result.toList()
        }
    }
}
