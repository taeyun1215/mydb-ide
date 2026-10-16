package dev.teo.mydb.plugin.objects

import dev.teo.mydb.core.ddl.ConstraintNaming
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgAlterGenerator
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.model.DbCheck
import dev.teo.mydb.core.model.DbColumn
import dev.teo.mydb.core.model.DbIndex
import dev.teo.mydb.core.model.DbForeignKey
import dev.teo.mydb.core.model.DbKey
import dev.teo.mydb.core.model.DbSchema
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.core.model.RelationKind

/** Column being edited; [originalName] is set for columns of the stored table (renames are tracked). */
class ColumnDraft(var name: String, var type: String = "integer", var notNull: Boolean = false, var default: String? = null,
                  var identity: Boolean = false, var comment: String? = null, val originalName: String? = null,
                  /** `generated always as (…) stored` expression; replaces the default. */
                  var generated: String? = null)

/** Index that does not back a key; expressions are allowed as columns. */
class IndexDraft(var name: String = "", var autoName: Boolean = true, var unique: Boolean = false, var method: String = "btree",
                 val columns: MutableList<String> = mutableListOf(), var predicate: String? = null, val originalName: String? = null)

/** Check constraint; [expression] is used as typed (parentheses are added when missing). */
class CheckDraft(var name: String = "", var autoName: Boolean = true, var expression: String = "", val originalName: String? = null)

/** Primary or unique key; the name follows the naming rule while [autoName] is true. */
class KeyDraft(var primary: Boolean, var name: String = "", var autoName: Boolean = true, val columns: MutableList<String> = mutableListOf())

/** Foreign key: column pairs `column → target column`; the target column may be left to the target's key. */
class FkDraft(var name: String = "", var autoName: Boolean = true, var refTable: String? = null,
              val pairs: MutableList<Pair<String?, String?>> = mutableListOf())

/**
 * Editing model of the create/modify table dialog: produces the `create table` script or,
 * for an existing table, only the ALTER statements for what changed.
 */
class TableDraft(val schema: DbSchema, val original: DbTable?, val dbms: dev.teo.mydb.core.datasource.Dbms = dev.teo.mydb.core.datasource.Dbms.POSTGRES) {
    private val isPostgres get() = dbms == dev.teo.mydb.core.datasource.Dbms.POSTGRES
    var name: String = original?.name ?: "table_name"
    var comment: String? = original?.comment
    val columns: MutableList<ColumnDraft> = original?.columns?.map {
        ColumnDraft(it.name, it.type, it.notNull, it.default.takeUnless { _ -> it.generated }, it.isSerial || it.identity != null, it.comment,
            originalName = it.name, generated = it.default.takeIf { _ -> it.generated })
    }?.toMutableList() ?: mutableListOf()
    val indexes: MutableList<IndexDraft> = original?.indexes?.filter { !it.backsConstraint }?.map { i ->
        IndexDraft(i.name, i.name == ConstraintNaming.index(original.name, i.columns), i.unique, i.method, i.columns.toMutableList(), i.predicate, originalName = i.name)
    }?.toMutableList() ?: mutableListOf()
    val checks: MutableList<CheckDraft> = original?.checks?.map { ch ->
        CheckDraft(ch.name, false, ch.expression, originalName = ch.name)
    }?.toMutableList() ?: mutableListOf()
    var primaryKey: KeyDraft? = original?.primaryKey?.let { k ->
        KeyDraft(true, k.name, k.name == ConstraintNaming.primaryKey(original.name), k.columns.toMutableList())
    }
    val uniqueKeys: MutableList<KeyDraft> = original?.uniqueKeys?.map { k ->
        KeyDraft(false, k.name, k.name == ConstraintNaming.unique(original.name, k.columns), k.columns.toMutableList())
    }?.toMutableList() ?: mutableListOf()
    val foreignKeys: MutableList<FkDraft> = original?.foreignKeys?.map { f ->
        FkDraft(f.name, f.name == ConstraintNaming.foreignKey(original.name, f.refTable, f.refColumns), f.refTable,
            f.columns.zip(f.refColumns.ifEmpty { f.columns.map { null } }).toMutableList())
    }?.toMutableList() ?: mutableListOf()

    val isNew get() = original == null

    fun addColumn(): ColumnDraft {
        val base = "column_name"
        val n = generateSequence(0) { it + 1 }.map { if (it == 0) base else "${base}_$it" }.first { c -> columns.none { it.name == c } }
        return ColumnDraft(n).also { columns += it }
    }

    fun addPrimaryKey(vararg columns: String): KeyDraft = KeyDraft(true, columns = columns.toMutableList()).also { primaryKey = it; refreshAutoNames() }
    fun addUniqueKey(vararg columns: String): KeyDraft = KeyDraft(false, columns = columns.toMutableList()).also { uniqueKeys += it; refreshAutoNames() }
    fun addForeignKey(): FkDraft = FkDraft().also { foreignKeys += it; refreshAutoNames() }
    fun addIndex(vararg columns: String): IndexDraft = IndexDraft(columns = columns.toMutableList()).also { indexes += it; refreshAutoNames() }
    fun addCheck(expression: String = ""): CheckDraft = CheckDraft(expression = expression).also { checks += it; refreshAutoNames() }
    fun renameIndex(i: IndexDraft, newName: String) { i.name = newName; i.autoName = newName == expectedName(i) }
    fun renameCheck(c: CheckDraft, newName: String) { c.name = newName; c.autoName = newName == expectedName(c) }

    private fun expectedName(i: IndexDraft) = ConstraintNaming.index(name, i.columns)
    /** Several unnamed checks get a numeric suffix so the names stay distinct. */
    private fun expectedName(c: CheckDraft): String {
        val base = ConstraintNaming.check(name, null)
        val autos = checks.filter { it.autoName || it === c }
        val n = autos.indexOf(c)
        return if (n <= 0) base else "${base}_$n"
    }

    /** User edit of a constraint name: it stops following the naming rule unless it equals the rule. */
    fun renameKey(key: KeyDraft, newName: String) { key.name = newName; key.autoName = newName == expectedName(key) }
    fun renameForeignKey(fk: FkDraft, newName: String) { fk.name = newName; fk.autoName = newName == expectedName(fk) }

    private fun expectedName(k: KeyDraft) = if (k.primary) ConstraintNaming.primaryKey(name) else ConstraintNaming.unique(name, k.columns)
    /** The column part of an FK name is the target column (`product_id → id` gives `reviews_products_id_fk`). */
    private fun expectedName(f: FkDraft) = ConstraintNaming.foreignKey(name, f.refTable, f.pairs.mapNotNull { it.second })

    /** Recomputes automatic names after a change of table name, columns or targets. */
    fun refreshAutoNames() {
        primaryKey?.let { if (it.autoName) it.name = expectedName(it) }
        uniqueKeys.forEach { if (it.autoName) it.name = expectedName(it) }
        foreignKeys.forEach { if (it.autoName) it.name = expectedName(it) }
        indexes.forEach { if (it.autoName) it.name = expectedName(it) }
        checks.forEach { if (it.autoName) it.name = expectedName(it) }
    }

    /** Incomplete definitions shown as errors in the preview. */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("Table name is empty")
        if (columns.isEmpty()) add("Table has no columns")
        columns.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { add("Duplicate column name: $it") }
        columns.filter { it.name.isBlank() }.forEach { _ -> add("Column name is empty") }
        columns.filter { it.type.isBlank() }.forEach { add("Column ${it.name} has no type") }
        primaryKey?.let { k -> if (k.columns.isEmpty()) add("Primary key ${k.name} has no columns") }
        uniqueKeys.filter { it.columns.isEmpty() }.forEach { add("Unique key ${it.name} has no columns") }
        val names = columns.map { it.name }.toSet()
        (listOfNotNull(primaryKey) + uniqueKeys).flatMap { it.columns }.filter { it !in names }.forEach { add("Key column $it does not exist") }
        foreignKeys.forEach { f ->
            if (f.refTable == null) add("Foreign key ${f.name} has no target table")
            if (f.pairs.none { it.first != null }) add("Foreign key ${f.name} has no columns")
            f.pairs.mapNotNull { it.first }.filter { it !in names }.forEach { add("Foreign key column $it does not exist") }
        }
        indexes.filter { it.columns.isEmpty() }.forEach { add("Index ${it.name} has no columns") }
        checks.filter { it.expression.isBlank() }.forEach { add("Check ${it.name} has no expression") }
        columns.filter { it.generated != null && it.generated!!.isBlank() }.forEach { add("Generated column ${it.name} has no expression") }
        if (!isPostgres) { runCatching { statements() }; addAll(dialectProblems) }
    }

    fun toDbTable(): DbTable = DbTable(
        oid = original?.oid ?: 0,
        name = name,
        kind = original?.kind ?: RelationKind.TABLE,
        columns = columns.mapIndexed { i, c ->
            val gen = c.generated?.takeIf { it.isNotBlank() }
            if (!isPostgres) {
                // MySQL auto_increment / SQLite autoincrement come from the identity flag, not a sequence default
                DbColumn(c.name, i + 1, c.type, c.notNull, gen ?: c.default?.takeUnless { c.identity }, comment = c.comment, generated = gen != null,
                    identity = if (c.identity) dev.teo.mydb.core.model.IdentityKind.BY_DEFAULT else null)
            } else {
                val default = if (c.identity && c.type in INTEGER_TYPES) (c.default?.takeIf { it.startsWith("nextval(") } ?: "nextval('${schema.name}.${name}_${c.name}_seq'::regclass)") else c.default
                DbColumn(c.name, i + 1, c.type, c.notNull, gen ?: default, comment = c.comment, generated = gen != null)
            }
        },
        primaryKey = primaryKey?.let { DbKey(it.name, it.columns.toList(), primary = true) },
        uniqueKeys = uniqueKeys.map { DbKey(it.name, it.columns.toList(), primary = false) },
        foreignKeys = foreignKeys.filter { it.refTable != null }.map { f ->
            val cols = f.pairs.mapNotNull { it.first }
            val refCols = f.pairs.filter { it.first != null }.map { it.second }
            DbForeignKey(f.name, cols, schema.name, f.refTable!!, if (refCols.all { it != null }) refCols.filterNotNull() else emptyList())
        },
        indexes = original?.indexes.orEmpty().filter { it.backsConstraint } +
            indexes.filter { it.columns.isNotEmpty() }.map { DbIndex(it.name, it.columns.toList(), it.unique, it.method, false, it.predicate?.ifBlank { null }) },
        checks = checks.filter { it.expression.isNotBlank() }.map { DbCheck(it.name, it.expression) },
        comment = comment?.takeIf { it.isNotBlank() },
        owner = original?.owner,
    )

    /** Statements to execute: `create table …` for a new table, else the ALTERs. */
    fun statements(): List<String> {
        val table = toDbTable()
        if (!isPostgres) {
            val g = dev.teo.mydb.core.ddl.DialectDdl(dev.teo.mydb.core.exec.SqlDialect.of(dbms), schema.name)
            val renames = columns.filter { it.originalName != null && it.originalName != it.name }.associate { it.originalName!! to it.name }
            val indexRenames = indexes.filter { it.originalName != null && it.originalName != it.name }.associate { it.originalName!! to it.name }
            val out = if (original == null) g.create(table) else g.alter(original, table, renames, indexRenames)
            dialectProblems = g.unsupported.toList()
            return out
        }
        if (original == null) return listOf(PgDdlGenerator(DdlOptions(skipOwner = true)) { _, t -> schema.table(t) }.table(schema.name, table))
        val renames = columns.filter { it.originalName != null && it.originalName != it.name }.associate { it.originalName!! to it.name }
        // compare like with like: the stored table seen through the draft's column defaults
        val checkRenames = checks.filter { it.originalName != null && it.originalName != it.name }.associate { it.originalName!! to it.name }
        val indexRenames = indexes.filter { it.originalName != null && it.originalName != it.name }.associate { it.originalName!! to it.name }
        return PgAlterGenerator(schema.name).alter(original, table, PgAlterGenerator.Renames(columns = renames, checks = checkRenames, indexes = indexRenames))
    }

    fun preview(): String = statements().joinToString("\n\n")

    /** What the DBMS cannot do with this draft (filled by [statements]). */
    private var dialectProblems: List<String> = emptyList()

    /** Column types offered in the type combo for the DBMS. */
    val typeChoices: List<String> get() = when (dbms) {
        dev.teo.mydb.core.datasource.Dbms.MYSQL -> dev.teo.mydb.core.ddl.DialectDdl.MYSQL_TYPES
        dev.teo.mydb.core.datasource.Dbms.SQLITE -> dev.teo.mydb.core.ddl.DialectDdl.SQLITE_TYPES
        else -> COMMON_TYPES
    }

    companion object {
        private val INTEGER_TYPES = setOf("integer", "bigint", "smallint")
        val COMMON_TYPES = listOf("integer", "bigint", "smallint", "numeric", "double precision", "real", "boolean", "text",
            "varchar(255)", "char(1)", "date", "time", "timestamp", "timestamp with time zone", "uuid", "json", "jsonb", "bytea")
    }
}
