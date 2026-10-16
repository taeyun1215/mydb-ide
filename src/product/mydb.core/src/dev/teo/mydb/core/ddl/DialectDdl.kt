package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.exec.SqlDialect
import dev.teo.mydb.core.model.*

/**
 * `create table` and ALTER statements of the create/modify dialog for MySQL and SQLite.
 * PostgreSQL keeps its own generators ([PgDdlGenerator], [PgAlterGenerator]).
 */
class DialectDdl(private val dialect: SqlDialect, private val schema: String) {
    private fun q(s: String) = dialect.quote(s)
    private fun t(name: String) = if (dialect == SqlDialect.SQLITE && schema == "main") q(name) else dialect.qualified(schema, name)

    /** Problems the dialect cannot express; shown in the dialog like incomplete definitions. */
    val unsupported = mutableListOf<String>()

    private fun createTable(table: DbTable): List<String> {
        val singlePk = table.primaryKey?.columns?.singleOrNull()
        val lines = table.columns.map { c ->
            buildString {
                append("    ").append(q(c.name)).append(' ').append(c.type)
                val autoPk = c.identity != null && c.name == singlePk
                when {
                    c.generated && c.default != null -> append(" generated always as ").append(PgDdlGenerator.parenthesized(c.default)).append(" stored")
                    dialect == SqlDialect.SQLITE && autoPk -> {}
                    c.default != null -> append(" default ").append(c.default)
                }
                if (c.notNull && !(dialect == SqlDialect.SQLITE && autoPk)) append(" not null")
                if (dialect == SqlDialect.MYSQL && c.identity != null) append(" auto_increment")
                if (c.name == singlePk) {
                    append(" primary key")
                    if (dialect == SqlDialect.SQLITE && c.identity != null) append(" autoincrement")
                }
                if (dialect == SqlDialect.MYSQL && c.comment != null) append(" comment ").append(literal(c.comment))
            }
        }.toMutableList()
        table.primaryKey?.takeIf { it.columns.size > 1 }?.let { lines += "    primary key (${it.columns.joinToString(", ") { c -> q(c) }})" }
        table.uniqueKeys.forEach { lines += "    constraint ${q(it.name)} unique (${it.columns.joinToString(", ") { c -> q(c) }})" }
        table.foreignKeys.forEach { f ->
            var s = "    constraint ${q(f.name)} foreign key (${f.columns.joinToString(", ") { q(it) }}) references ${t(f.refTable)}"
            if (f.refColumns.isNotEmpty()) s += " (${f.refColumns.joinToString(", ") { q(it) }})"
            if (f.onDelete != FkAction.NO_ACTION) s += " on delete ${f.onDelete.sql}"
            if (f.onUpdate != FkAction.NO_ACTION) s += " on update ${f.onUpdate.sql}"
            lines += s
        }
        table.checks.forEach { lines += "    constraint ${q(it.name)} check ${PgDdlGenerator.parenthesized(it.expression)}" }
        val tail = if (dialect == SqlDialect.MYSQL && table.comment != null) " comment ${literal(table.comment)}" else ""
        if (dialect == SqlDialect.SQLITE && (table.comment != null || table.columns.any { it.comment != null })) unsupported += "SQLite has no comments; they are not saved"
        return listOf("create table ${t(table.name)}\n(\n${lines.joinToString(",\n")}\n)$tail;") +
            table.indexes.filter { !it.backsConstraint }.map(::createIndex)
    }

    private fun createIndex(i: DbIndex) =
        "create ${if (i.unique) "unique " else ""}index ${q(i.name)} on ${t(currentTable)} (${i.columns.joinToString(", ") { q(it) }})" +
            (i.predicate?.takeIf { dialect == SqlDialect.SQLITE }?.let { " where $it" } ?: "") + ";"

    private var currentTable = ""

    fun create(table: DbTable): List<String> { currentTable = table.name; return createTable(table.copy(foreignKeys = table.foreignKeys.filter { !it.virtual })) }

    /** ALTERs for [new] against the stored [old] table; renames are explicit as in [PgAlterGenerator]. */
    fun alter(oldTable: DbTable, newTable: DbTable, columnRenames: Map<String, String> = emptyMap(), indexRenames: Map<String, String> = emptyMap()): List<String> {
        val old = oldTable.copy(foreignKeys = oldTable.foreignKeys.filter { !it.virtual })
        val new = newTable.copy(foreignKeys = newTable.foreignKeys.filter { !it.virtual })
        val out = mutableListOf<String>()
        var current = old.name
        currentTable = current
        if (new.name != old.name) {
            out += if (dialect == SqlDialect.MYSQL) "rename table ${t(old.name)} to ${t(new.name)};" else "alter table ${t(old.name)} rename to ${q(new.name)};"
            current = new.name; currentTable = current
        }
        columnRenames.forEach { (from, to) -> out += "alter table ${t(current)} rename column ${q(from)} to ${q(to)};" }
        val oldCols = old.columns.associateBy { columnRenames[it.name] ?: it.name }
        for (c in new.columns) {
            val o = oldCols[c.name]
            if (o == null) {
                out += "alter table ${t(current)} add ${columnDef(c)};"
                continue
            }
            if (o.type != c.type || o.notNull != c.notNull || o.default != c.default || o.comment != c.comment) {
                if (dialect == SqlDialect.MYSQL) out += "alter table ${t(current)} modify ${columnDef(c)};"
                else unsupported += "SQLite cannot change column ${c.name}; the table must be rebuilt"
            }
        }
        val newNames = new.columns.map { it.name }.toSet()
        oldCols.keys.filter { it !in newNames }.forEach { out += "alter table ${t(current)} drop column ${q(it)};" }
        // indexes
        val oldIdx = old.indexes.filter { !it.backsConstraint }
        val newIdx = new.indexes.filter { !it.backsConstraint }.associateBy { it.name }
        for (i in oldIdx) {
            val name = indexRenames[i.name] ?: i.name
            val now = newIdx[name]
            when {
                now == null -> out += dropIndex(i.name, current)
                now.copy(name = i.name) != i || name != i.name -> { out += dropIndex(i.name, current); out += createIndex(now) }
            }
        }
        val kept = oldIdx.map { indexRenames[it.name] ?: it.name }.toSet()
        newIdx.values.filter { it.name !in kept }.forEach { out += createIndex(it) }
        // constraints added in the dialog
        if (old.primaryKey == null && new.primaryKey != null) {
            if (dialect == SqlDialect.MYSQL) out += "alter table ${t(current)} add primary key (${new.primaryKey.columns.joinToString(", ") { q(it) }});"
            else unsupported += "SQLite cannot add a primary key to an existing table"
        }
        val oldFks = old.foreignKeys.map { it.name }.toSet()
        new.foreignKeys.filter { it.name !in oldFks }.forEach { f ->
            if (dialect == SqlDialect.MYSQL) out += "alter table ${t(current)} add constraint ${q(f.name)} foreign key (${f.columns.joinToString(", ") { q(it) }}) references ${t(f.refTable)}" +
                (if (f.refColumns.isNotEmpty()) " (${f.refColumns.joinToString(", ") { q(it) }})" else "") + ";"
            else unsupported += "SQLite cannot add a foreign key to an existing table"
        }
        val newFks = new.foreignKeys.map { it.name }.toSet()
        old.foreignKeys.filter { it.name !in newFks }.forEach {
            if (dialect == SqlDialect.MYSQL) out += "alter table ${t(current)} drop foreign key ${q(it.name)};"
            else unsupported += "SQLite cannot drop a foreign key without rebuilding the table"
        }
        if (dialect == SqlDialect.MYSQL && new.comment != old.comment) out += "alter table ${t(current)} comment = ${literal(new.comment.orEmpty())};"
        return out
    }

    private fun dropIndex(name: String, table: String) =
        if (dialect == SqlDialect.MYSQL) "drop index ${q(name)} on ${t(table)};" else "drop index ${t(name)};"

    private fun columnDef(c: DbColumn) = buildString {
        append(q(c.name)).append(' ').append(c.type)
        if (c.generated && c.default != null) append(" generated always as ").append(PgDdlGenerator.parenthesized(c.default)).append(" stored")
        else c.default?.let { append(" default ").append(it) }
        if (c.notNull) append(" not null")
        if (dialect == SqlDialect.MYSQL && c.identity != null) append(" auto_increment")
        if (dialect == SqlDialect.MYSQL && c.comment != null) append(" comment ").append(literal(c.comment))
    }

    private fun literal(s: String) = "'" + s.replace("'", "''") + "'"

    companion object {
        val MYSQL_TYPES = listOf("int", "bigint", "smallint", "tinyint", "decimal(10,2)", "double", "float", "boolean", "varchar(255)", "char(1)",
            "text", "longtext", "date", "datetime", "timestamp", "time", "json", "blob")
        val SQLITE_TYPES = listOf("integer", "real", "numeric", "text", "blob", "boolean", "date", "datetime")
    }
}
