package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.normalizeType
import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote
import dev.teo.mydb.core.model.*

/**
 * Names given to constraints created in the object dialog. A name is "automatic"
 * while it equals what these rules produce; automatic names follow table/column renames.
 */
object ConstraintNaming {
    fun primaryKey(table: String) = "${table}_pk"
    fun unique(table: String, columns: List<String>) = "${table}_${columns.joinToString("_")}_uk"
    fun foreignKey(table: String, refTable: String?, columns: List<String>) =
        listOf(table, refTable.orEmpty(), columns.joinToString("_"), "fk").joinToString("_")
    fun check(table: String, column: String?) = "${table}${column?.let { "_$it" } ?: ""}_check"
    fun index(table: String, columns: List<String>) = "${table}_${columns.joinToString("_")}_index"
}

/**
 * Generates ALTER statements for the modify dialog: the edited table [new] against the stored [old]
 * one. Renames are explicit because names are the identity.
 */
class PgAlterGenerator(private val schema: String) {

    /** Explicit renames: table, columns, and check constraints / indexes by their stored name. */
    data class Renames(val table: String? = null, val columns: Map<String, String> = emptyMap(),
                       val checks: Map<String, String> = emptyMap(), val indexes: Map<String, String> = emptyMap())

    fun alter(oldTable: DbTable, newTable: DbTable, renames: Renames = Renames()): List<String> {
        val old = oldTable.copy(foreignKeys = oldTable.foreignKeys.filter { !it.virtual })
        val new = newTable.copy(foreignKeys = newTable.foreignKeys.filter { !it.virtual })
        val out = mutableListOf<String>()
        val oldName = old.name
        val newName = renames.table ?: new.name
        var current = oldName
        fun t() = "${quote(schema)}.${quote(current)}"

        // 1. constraint renames (automatic names follow the table name)
        val constraintPairs = buildList {
            if (old.primaryKey != null && new.primaryKey != null) add(old.primaryKey.name to new.primaryKey.name)
            old.foreignKeys.zip(new.foreignKeys).forEach { (a, b) -> add(a.name to b.name) }
            old.uniqueKeys.zip(new.uniqueKeys).forEach { (a, b) -> add(a.name to b.name) }
        }
        constraintPairs.filter { (a, b) -> a != b }.forEach { (a, b) -> out += "alter table ${t()}\n    rename constraint ${quote(a)} to ${quote(b)};" }

        // 2. table rename
        if (newName != oldName) { out += "alter table ${t()}\n    rename to ${quote(newName)};"; current = newName }

        // 3. column renames
        renames.columns.forEach { (from, to) -> out += "alter table ${t()}\n    rename column ${quote(from)} to ${quote(to)};" }
        val oldCols = old.columns.associateBy { renames.columns[it.name] ?: it.name }

        // 4. added / changed / dropped columns
        for (c in new.columns) {
            val o = oldCols[c.name]
            if (o == null) {
                val tail = if (c.generated && c.default != null) " generated always as ${PgDdlGenerator.parenthesized(c.default)} stored" else c.default?.let { " default $it" } ?: ""
                out += "alter table ${t()}\n    add ${quote(c.name)} ${normalizeType(c.type)}$tail${if (c.notNull) " not null" else ""};"
                continue
            }
            if (o.type != c.type) out += "alter table ${t()}\n    alter column ${quote(c.name)} type ${normalizeType(c.type)} using ${quote(c.name)}::${normalizeType(c.type)};"
            if (o.default != c.default) out += "alter table ${t()}\n    alter column ${quote(c.name)} ${c.default?.let { "set default $it" } ?: "drop default"};"
            if (o.notNull != c.notNull) out += "alter table ${t()}\n    alter column ${quote(c.name)} ${if (c.notNull) "set" else "drop"} not null;"
        }
        val newNames = new.columns.map { it.name }.toSet()
        oldCols.keys.filter { it !in newNames }.forEach { out += "alter table ${t()}\n    drop column ${quote(it)};" }

        // 5. constraints added in the dialog
        if (old.primaryKey == null && new.primaryKey != null)
            out += "alter table ${t()}\n    add constraint ${quote(new.primaryKey.name)}\n        primary key (${new.primaryKey.columns.joinToString(", ") { quote(it) }});"
        val oldFkNames = old.foreignKeys.map { it.name }.toSet() + constraintPairs.map { it.second }
        new.foreignKeys.filter { it.name !in oldFkNames }.forEach { f ->
            out += "alter table ${t()}\n    add constraint ${quote(f.name)}\n        foreign key (${f.columns.joinToString(", ") { quote(it) }}) references ${quote(f.refSchema)}.${quote(f.refTable)};"
        }
        // 6. check constraints: rename, drop, re-create when the expression changed, add
        val newChecks = new.checks.associateBy { it.name }
        for (ch in old.checks) {
            val name = renames.checks[ch.name] ?: ch.name
            val now = newChecks[name]
            when {
                now == null -> out += "alter table ${t()}\n    drop constraint ${quote(ch.name)};"
                now.expression != ch.expression -> {
                    out += "alter table ${t()}\n    drop constraint ${quote(ch.name)};"
                    out += "alter table ${t()}\n    add constraint ${quote(name)} check ${PgDdlGenerator.parenthesized(now.expression)};"
                }
                name != ch.name -> out += "alter table ${t()}\n    rename constraint ${quote(ch.name)} to ${quote(name)};"
            }
        }
        val keptChecks = old.checks.map { renames.checks[it.name] ?: it.name }.toSet()
        new.checks.filter { it.name !in keptChecks }.forEach {
            out += "alter table ${t()}\n    add constraint ${quote(it.name)} check ${PgDdlGenerator.parenthesized(it.expression)};"
        }

        // 7. indexes (not those backing a key): rename, drop, re-create when changed, create
        val oldIdx = old.indexes.filter { !it.backsConstraint }
        val newIdx = new.indexes.filter { !it.backsConstraint }.associateBy { it.name }
        val gen = PgDdlGenerator(DdlOptions(skipOwner = true))
        for (i in oldIdx) {
            val name = renames.indexes[i.name] ?: i.name
            val now = newIdx[name]
            when {
                now == null -> out += "drop index ${quote(schema)}.${quote(i.name)};"
                now.copy(name = i.name) != i -> { out += "drop index ${quote(schema)}.${quote(i.name)};"; out += gen.index(schema, current, now) }
                name != i.name -> out += "alter index ${quote(schema)}.${quote(i.name)}\n    rename to ${quote(name)};"
            }
        }
        val keptIdx = oldIdx.map { renames.indexes[it.name] ?: it.name }.toSet()
        newIdx.values.filter { it.name !in keptIdx }.forEach { out += gen.index(schema, current, it) }

        if (new.comment != old.comment) out += "comment on table ${t()} is ${new.comment?.let { "'" + it.replace("'", "''") + "'" } ?: "null"};"
        return out
    }

    /** Rename refactoring. */
    fun renameColumn(table: String, from: String, to: String) =
        "alter table ${quote(schema)}.${quote(table)}\n    rename column ${quote(from)} to ${quote(to)};"

    fun renameTable(table: String, to: String) =
        "alter table ${quote(schema)}.${quote(table)}\n    rename to ${quote(to)};"
}
