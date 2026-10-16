package dev.teo.mydb.core.diff

import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.normalizeType
import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote
import dev.teo.mydb.core.model.*

/** Status colours of the comparison tree. */
enum class DiffStatus { ONLY_IN_SOURCE, ONLY_IN_TARGET, CHANGED, SAME }

/** One line of the comparison tree; [id] is stable so the UI can keep checkbox state. */
data class DiffItem(
    val id: String,
    val kind: String,
    val name: String,
    val status: DiffStatus,
    val source: String? = null,
    val target: String? = null,
    val children: List<DiffItem> = emptyList(),
) {
    fun flatten(): List<DiffItem> = listOf(this) + children.flatMap { it.flatten() }
}

/** A migration statement belonging to a diff item; [phase] orders the script. */
data class MigrationStatement(val itemId: String, val phase: Int, val sql: String)

/**
 * Compares two schemas (source → target) and produces the script that makes the target like the source.
 * Matching is by name.
 */
class SchemaDiff(val source: DbSchema, val target: DbSchema, private val lookupSource: (String) -> DbTable? = { source.table(it) },
                 /** MySQL and SQLite targets get their own DDL; the items are compared the same way. */
                 private val dialect: dev.teo.mydb.core.exec.SqlDialect = dev.teo.mydb.core.exec.SqlDialect.POSTGRES) {

    private val tgtName = target.name
    private val gen = PgDdlGenerator(DdlOptions(targetSchema = tgtName)) { _, t -> lookupSource(t) }

    val items: List<DiffItem> by lazy { buildItems().also { if (dialect != dev.teo.mydb.core.exec.SqlDialect.POSTGRES) dialectStatements(it) } }
    private val statements = mutableListOf<MigrationStatement>()

    private fun buildItems(): List<DiffItem> {
        statements.clear()
        val names = (source.tables.map { it.name } + target.tables.map { it.name }).distinct().sorted()
        return names.map { n ->
            val s = source.table(n)
            val t = target.table(n)
            when {
                s != null && t == null -> created(s)
                s == null && t != null -> dropped(t)
                else -> compare(s!!, t!!)
            }
        }
    }

    /**
     * Statements for a MySQL/SQLite target: whole-table create, drop or ALTERs from [dev.teo.mydb.core.ddl.DialectDdl]. They belong to
     * the table item, so checking columns of a changed table individually has no effect there.
     */
    private fun dialectStatements(items: List<DiffItem>) {
        statements.clear()
        val order = PgDdlGenerator.orderByDependencies(source.tables.filter { !it.isView }.sortedBy { it.oid })
        for (item in items) {
            val s = source.table(item.name); val t = target.table(item.name)
            // SQLite's main schema is implied, as in DialectDdl
            val qualified = if (dialect == dev.teo.mydb.core.exec.SqlDialect.SQLITE && tgtName == "main") dialect.quote(item.name) else dialect.qualified(tgtName, item.name)
            when (item.status) {
                DiffStatus.ONLY_IN_SOURCE -> if (s!!.isView) statements += MigrationStatement(item.id, PHASE_CREATE_VIEW,
                        "create view $qualified as\n${s.viewDefinition?.trim()?.removeSuffix(";") ?: "select null"};")
                    else dev.teo.mydb.core.ddl.DialectDdl(dialect, tgtName).create(s).forEach {
                        statements += MigrationStatement(item.id, PHASE_CREATE_TABLE + order.indexOf(s), it)
                    }
                DiffStatus.ONLY_IN_TARGET -> statements += MigrationStatement(item.id, if (t!!.isView) PHASE_DROP_VIEW else PHASE_DROP_TABLE,
                    "drop ${if (t.isView) "view" else "table"} $qualified;")
                DiffStatus.CHANGED -> if (s!!.isView) {
                    statements += MigrationStatement(item.id, PHASE_DROP_VIEW, "drop view $qualified;")
                    statements += MigrationStatement(item.id, PHASE_CREATE_VIEW, "create view $qualified as\n${s.viewDefinition?.trim()?.removeSuffix(";")};")
                } else dev.teo.mydb.core.ddl.DialectDdl(dialect, tgtName).alter(t!!, s.copy(name = t.name)).forEachIndexed { i, sql ->
                    statements += MigrationStatement(item.id, PHASE_ALTER_COLUMN + i, sql)
                }
                DiffStatus.SAME -> {}
            }
        }
    }

    /** Script for the checked items (all by default), in phase order. */
    fun script(included: Set<String>? = null): String {
        items // ensure built
        return statements.filter { included == null || it.itemId in included }
            .sortedWith(compareBy({ it.phase }))
            .joinToString("\n\n") { it.sql }
    }

    private var alteredTables = 0

    private fun tname(t: String) = "${quote(tgtName)}.${quote(t)}"

    private fun created(s: DbTable): DiffItem {
        val id = "table:${s.name}"
        if (s.isView) {
            statements += MigrationStatement(id, PHASE_CREATE_VIEW, gen.view(source.name, s))
        } else {
            // creation (OID) order, then foreign-key dependencies
            val order = PgDdlGenerator.orderByDependencies(source.tables.filter { !it.isView }.sortedBy { it.oid }).indexOf(s)
            statements += MigrationStatement(id, PHASE_CREATE_TABLE + order, gen.table(source.name, s))
        }
        return DiffItem(id, if (s.isView) "view" else "table", s.name, DiffStatus.ONLY_IN_SOURCE, source = s.name)
    }

    private fun dropped(t: DbTable): DiffItem {
        val id = "table:${t.name}"
        statements += MigrationStatement(id, if (t.isView) PHASE_DROP_VIEW else PHASE_DROP_TABLE,
            "drop ${if (t.kind == RelationKind.MATERIALIZED_VIEW) "materialized view" else if (t.isView) "view" else "table"} ${tname(t.name)};")
        return DiffItem(id, if (t.isView) "view" else "table", t.name, DiffStatus.ONLY_IN_TARGET, target = t.name)
    }

    private fun compare(s: DbTable, t: DbTable): DiffItem {
        val id = "table:${s.name}"
        // column type changes and column drops of one table stay together, table by table
        val columnPhase = PHASE_ALTER_COLUMN + (++alteredTables)
        val children = mutableListOf<DiffItem>()
        if (s.isView || t.isView) {
            if (s.viewDefinition?.trim() != t.viewDefinition?.trim() || s.kind != t.kind) {
                statements += MigrationStatement(id, PHASE_DROP_VIEW, "drop view ${tname(t.name)};")
                statements += MigrationStatement(id, PHASE_CREATE_VIEW, gen.view(source.name, s))
                return DiffItem(id, "view", s.name, DiffStatus.CHANGED, s.name, t.name)
            }
            return DiffItem(id, "view", s.name, DiffStatus.SAME, s.name, t.name)
        }
        // columns
        for (name in (s.columns.map { it.name } + t.columns.map { it.name }).distinct()) {
            val sc = s.column(name)
            val tc = t.column(name)
            val cid = "$id/column:$name"
            when {
                sc != null && tc == null -> {
                    statements += MigrationStatement(cid, PHASE_ADD_COLUMN, "alter table ${tname(t.name)}\n    add ${columnDef(sc)};")
                    children += DiffItem(cid, "column", name, DiffStatus.ONLY_IN_SOURCE, sc.type, null)
                }
                sc == null && tc != null -> {
                    statements += MigrationStatement(cid, columnPhase, "alter table ${tname(t.name)}\n    drop column ${quote(name)};")
                    children += DiffItem(cid, "column", name, DiffStatus.ONLY_IN_TARGET, null, tc.type)
                }
                else -> {
                    val alters = mutableListOf<String>()
                    if (sc!!.type != tc!!.type) {
                        val nt = normalizeType(sc.type)
                        alters += "alter column ${quote(name)} type $nt using ${quote(name)}::$nt"
                    }
                    if (sc.default != tc.default && !(sc.isSerial && tc.isSerial)) {
                        alters += if (sc.default == null) "alter column ${quote(name)} drop default" else "alter column ${quote(name)} set default ${sc.default}"
                    }
                    if (sc.notNull != tc.notNull) alters += "alter column ${quote(name)} ${if (sc.notNull) "set" else "drop"} not null"
                    if (alters.isNotEmpty()) {
                        alters.forEach { statements += MigrationStatement(cid, columnPhase, "alter table ${tname(t.name)}\n    $it;") }
                        children += DiffItem(cid, "column", name, DiffStatus.CHANGED, sc.type, tc.type)
                    } else children += DiffItem(cid, "column", name, DiffStatus.SAME, sc.type, tc.type)
                }
            }
        }
        // keys and constraints, compared by definition (names may differ between schemas)
        fun keySig(k: DbKey) = "${k.primary}:${k.columns}"
        val tKeys = (listOfNotNull(t.primaryKey) + t.uniqueKeys).associateBy(::keySig)
        val sKeys = (listOfNotNull(s.primaryKey) + s.uniqueKeys).associateBy(::keySig)
        for ((sig, k) in sKeys) if (sig !in tKeys) {
            val kid = "$id/key:${k.name}"
            val clause = if (k.primary) "primary key" else "unique"
            val named = if (k.name == defaultKeyName(s, k)) "" else "constraint ${quote(k.name)} "
            statements += MigrationStatement(kid, PHASE_ADD_CONSTRAINT, "alter table ${tname(t.name)}\n    add $named$clause (${k.columns.joinToString(", ") { quote(it) }});")
            children += DiffItem(kid, "key", k.name, DiffStatus.ONLY_IN_SOURCE, k.name)
        }
        for ((sig, k) in tKeys) if (sig !in sKeys) {
            val kid = "$id/key:${k.name}"
            statements += MigrationStatement(kid, PHASE_DROP_CONSTRAINT, "alter table ${tname(t.name)}\n    drop constraint ${quote(k.name)};")
            children += DiffItem(kid, "key", k.name, DiffStatus.ONLY_IN_TARGET, target = k.name)
        }
        val tChecks = t.checks.map { it.expression }.toSet()
        s.checks.filter { it.expression !in tChecks }.forEach { c ->
            val kid = "$id/check:${c.name}"
            statements += MigrationStatement(kid, PHASE_ADD_CONSTRAINT, "alter table ${tname(t.name)}\n    add constraint ${quote(c.name)}\n        check ${c.expression};")
            children += DiffItem(kid, "check", c.name, DiffStatus.ONLY_IN_SOURCE, c.expression)
        }
        val sChecks = s.checks.map { it.expression }.toSet()
        t.checks.filter { it.expression !in sChecks }.forEach { c ->
            val kid = "$id/check:${c.name}"
            statements += MigrationStatement(kid, PHASE_DROP_CONSTRAINT, "alter table ${tname(t.name)}\n    drop constraint ${quote(c.name)};")
            children += DiffItem(kid, "check", c.name, DiffStatus.ONLY_IN_TARGET, target = c.expression)
        }
        fun fkSig(f: DbForeignKey) = "${f.columns}->${f.refTable}${f.refColumns}:${f.onDelete}:${f.onUpdate}"
        val tFks = t.foreignKeys.filter { !it.virtual }.associateBy(::fkSig)
        val sFks = s.foreignKeys.filter { !it.virtual }.associateBy(::fkSig)
        for ((sig, f) in sFks) if (sig !in tFks) {
            val kid = "$id/fk:${f.name}"
            var sql = "alter table ${tname(t.name)}\n    add foreign key (${f.columns.joinToString(", ") { quote(it) }}) references ${tname(f.refTable)}"
            if (f.onDelete != FkAction.NO_ACTION) sql += "\n        on delete ${f.onDelete.sql}"
            statements += MigrationStatement(kid, PHASE_ADD_FK, "$sql;")
            children += DiffItem(kid, "foreign key", f.name, DiffStatus.ONLY_IN_SOURCE, f.name)
        }
        for ((sig, f) in tFks) if (sig !in sFks) {
            val kid = "$id/fk:${f.name}"
            statements += MigrationStatement(kid, PHASE_DROP_CONSTRAINT, "alter table ${tname(t.name)}\n    drop constraint ${quote(f.name)};")
            children += DiffItem(kid, "foreign key", f.name, DiffStatus.ONLY_IN_TARGET, target = f.name)
        }
        // secondary indexes by definition
        fun ixSig(i: DbIndex) = "${i.unique}:${i.method}:${i.columns}:${i.predicate}"
        val tIx = t.indexes.filter { !it.backsConstraint }.associateBy(::ixSig)
        val sIx = s.indexes.filter { !it.backsConstraint }.associateBy(::ixSig)
        for ((sig, i) in sIx) if (sig !in tIx) {
            statements += MigrationStatement("$id/index:${i.name}", PHASE_CREATE_INDEX, gen.index(source.name, s.name, i))
            children += DiffItem("$id/index:${i.name}", "index", i.name, DiffStatus.ONLY_IN_SOURCE, i.name)
        }
        for ((sig, i) in tIx) if (sig !in sIx) {
            statements += MigrationStatement("$id/index:${i.name}", PHASE_DROP_CONSTRAINT, "drop index ${quote(tgtName)}.${quote(i.name)};")
            children += DiffItem("$id/index:${i.name}", "index", i.name, DiffStatus.ONLY_IN_TARGET, target = i.name)
        }
        val status = if (children.all { it.status == DiffStatus.SAME }) DiffStatus.SAME else DiffStatus.CHANGED
        return DiffItem(id, "table", s.name, status, s.name, t.name, children)
    }

    private fun columnDef(c: DbColumn): String = buildString {
        append(quote(c.name)).append(' ').append(normalizeType(c.type))
        c.default?.let { append(" default ").append(it) }
        if (c.notNull) append(" not null")
    }

    private fun defaultKeyName(t: DbTable, k: DbKey) = if (k.primary) "${t.name}_pkey" else "${t.name}_${k.columns.joinToString("_")}_key"

    companion object {
        // phases in order: add columns, add constraints, create tables, alter columns, drop columns, drop tables, views
        const val PHASE_DROP_VIEW = 0
        const val PHASE_ADD_COLUMN = 1
        const val PHASE_ADD_CONSTRAINT = 2
        const val PHASE_CREATE_TABLE = 3_000_000 // + dependency order
        const val PHASE_ADD_FK = 3_500_000
        const val PHASE_CREATE_INDEX = 3_600_000
        const val PHASE_ALTER_COLUMN = 4_000_000
        const val PHASE_DROP_CONSTRAINT = 4_500_000
        const val PHASE_DROP_COLUMN = 5_000_000
        const val PHASE_DROP_TABLE = 6_000_000
        const val PHASE_CREATE_VIEW = 7_000_000
    }
}
