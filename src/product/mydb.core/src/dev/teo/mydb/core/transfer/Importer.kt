package dev.teo.mydb.core.transfer

import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote
import dev.teo.mydb.core.exec.Session
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate

/** RFC 4180 reader: quoted fields may contain the separator, `""` and line breaks; empty unquoted field → null. */
object CsvReader {
    fun read(reader: Reader, separator: Char = ','): List<List<String?>> {
        val text = reader.readText()
        val rows = mutableListOf<List<String?>>()
        var row = mutableListOf<String?>()
        val field = StringBuilder()
        var quoted = false
        var wasQuoted = false
        var i = 0
        fun endField() { row += if (field.isEmpty() && !wasQuoted) null else field.toString(); field.clear(); wasQuoted = false }
        fun endRow() { endField(); if (!(row.size == 1 && row[0] == null)) rows += row; row = mutableListOf() }
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> { quoted = true; wasQuoted = true }
                separator -> endField()
                '\r' -> {}
                '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || wasQuoted) endRow()
        return rows
    }
}

data class ImportColumn(val name: String, val type: String, val sourceIndex: Int)

data class ImportOptions(
    val firstRowIsHeader: Boolean = true,
    val separator: Char = ',',
    /** Values the column type cannot accept are inserted as NULL instead of failing. */
    val insertUnconvertibleAsNull: Boolean = false,
    val batchSize: Int = 1000,
    val errorLog: Path? = null,
)

/** Column types inferred for a new target table. */
object TypeInference {
    private val INT = Regex("[-+]?\\d+")
    private val NUM = Regex("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")
    private val BOOL = setOf("true", "false")

    fun infer(values: List<String?>): String {
        val present = values.filterNotNull()
        if (present.isEmpty()) return "text"
        return when {
            present.all { INT.matches(it) } -> if (present.all { it.toLongOrNull()?.let { v -> v in Int.MIN_VALUE..Int.MAX_VALUE } == true }) "integer" else "bigint"
            present.all { NUM.matches(it) } -> "double precision"
            present.all { it.lowercase() in BOOL } -> "boolean"
            // dates only when every cell is a date
            values.none { it == null } && present.all { runCatching { LocalDate.parse(it) }.isSuccess } -> "date"
            else -> "text"
        }
    }
}

data class ImportPlan(val schema: String, val table: String, val createTable: Boolean, val columns: List<ImportColumn>,
                      val dialect: dev.teo.mydb.core.exec.SqlDialect = dev.teo.mydb.core.exec.SqlDialect.POSTGRES) {
    private fun quote(s: String) = dialect.quote(s)
    fun ddl(): String = "create table ${quote(schema)}.${quote(table)}\n(\n" +
        columns.joinToString(",\n") { c -> "    " + quote(c.name).padEnd(columns.maxOf { quote(it.name).length }) + " " + c.type } + "\n);"
}

data class ImportResult(val inserted: Int, val failed: Int)

class Importer(private val session: Session) {

    /** Plan for a new table named after the file: header names, inferred types. */
    fun planNewTable(schema: String, file: Path, options: ImportOptions = ImportOptions()): ImportPlan = planFor(file, schema, null, options)

    /** Plan for an existing table: columns mapped by position. */
    fun planExisting(schema: String, table: String, targetColumns: List<Pair<String, String>>) =
        ImportPlan(schema, table, createTable = false, targetColumns.mapIndexed { i, (n, t) -> ImportColumn(n, t, i) })

    companion object {
        /** Import plan without a session: into [existing] (table name and its columns with types) or a new table. */
        fun planFor(file: Path, schema: String, existing: Pair<String, List<Pair<String, String>>>?, options: ImportOptions = ImportOptions()): ImportPlan {
            if (existing != null) {
                val (table, columns) = existing
                return ImportPlan(schema, table, createTable = false, columns.mapIndexed { i, (n, t) -> ImportColumn(n, t, i) })
            }
            val rows = Files.newBufferedReader(file).use { CsvReader.read(it, options.separator) }
            val header = if (options.firstRowIsHeader) rows.first().mapIndexed { i, h -> h ?: "column_${i + 1}" } else rows.first().indices.map { "column_${it + 1}" }
            val data = if (options.firstRowIsHeader) rows.drop(1) else rows
            val columns = header.mapIndexed { i, name -> ImportColumn(name, TypeInference.infer(data.map { it.getOrNull(i) }), i) }
            return ImportPlan(schema, file.fileName.toString().substringBeforeLast('.'), createTable = true, columns)
        }
    }

    /**
     * create table (if new) → BEGIN → multi-row INSERT batches with a savepoint each → COMMIT.
     */
    fun run(plan: ImportPlan, file: Path, options: ImportOptions = ImportOptions()): ImportResult {
        val rows = Files.newBufferedReader(file).use { CsvReader.read(it, options.separator) }.let { if (options.firstRowIsHeader) it.drop(1) else it }
        if (plan.createTable) session.connection.createStatement().use { it.execute(plan.ddl()) }
        val c = session.connection
        val dialect = dev.teo.mydb.core.exec.SqlDialect.of(session.connection)
        val target = dialect.qualified(plan.schema, plan.table)
        val colList = plan.columns.joinToString(", ") { dialect.quote(it.name) }
        var inserted = 0
        var failed = 0
        val errors = StringBuilder()
        session.inTransaction { conn ->
            for (batch in rows.chunked(options.batchSize)) {
                val sp = conn.setSavepoint()
                try {
                    insertBatch(conn, target, colList, plan, batch, options)
                    inserted += batch.size
                } catch (e: SQLException) {
                    conn.rollback(sp)
                    if (!options.insertUnconvertibleAsNull && options.errorLog == null) throw e
                    // retry row by row so one bad row does not lose the batch
                    for (r in batch) {
                        val rsp = conn.setSavepoint()
                        try { insertBatch(conn, target, colList, plan, listOf(r), options); inserted++ }
                        catch (e2: SQLException) {
                            conn.rollback(rsp)
                            if (options.insertUnconvertibleAsNull) {
                                insertBatch(conn, target, colList, plan, listOf(r.mapIndexed { i, v -> if (convertible(conn, plan.columns.firstOrNull { it.sourceIndex == i }?.type, v)) v else null }), options)
                                inserted++
                            } else { failed++; errors.appendLine("${r.joinToString(",")}\t${e2.message}") }
                        }
                    }
                }
            }
        }
        options.errorLog?.let { if (errors.isNotEmpty()) Files.writeString(it, errors) }
        return ImportResult(inserted, failed)
    }

    private fun insertBatch(conn: java.sql.Connection, target: String, colList: String, plan: ImportPlan, batch: List<List<String?>>, options: ImportOptions) {
        val tuple = plan.columns.joinToString(", ", "(", ")") { "?" }
        conn.prepareStatement("INSERT INTO $target ($colList) VALUES ${batch.joinToString(",") { tuple }}").use { ps ->
            var p = 1
            for (r in batch) for (col in plan.columns) {
                val v = r.getOrNull(col.sourceIndex)
                if (v == null) ps.setNull(p++, Types.NULL) else ps.setObject(p++, v, Types.OTHER)
            }
            ps.executeUpdate()
        }
    }

    private fun convertible(conn: java.sql.Connection, type: String?, value: String?): Boolean {
        if (value == null || type == null) return true
        val sp = conn.setSavepoint()
        return try {
            conn.prepareStatement("select ?::$type").use { it.setString(1, value); it.execute() }; true
        } catch (_: SQLException) { conn.rollback(sp); false }
    }
}
