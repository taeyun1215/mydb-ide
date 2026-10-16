package dev.teo.mydb.core.edit

import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.core.exec.Session
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types

/** Marker for "use the column default" in inserted rows. */
data object DefaultValue

/** A value typed by the user; sent untyped so the server converts it to the column type. */
data class UserText(val text: String)

/**
 * Pending (not yet submitted) changes of an editable result.
 * Row indexes refer to [page] rows; inserted rows are kept separately.
 */
class GridEdits(
    val schema: String,
    val table: String,
    val page: ResultPage,
    /** Primary key columns of the table; empty → all original values identify a row. */
    val keyColumns: List<String>,
    /** Columns that have a server default (rendered `<default>` in new rows). */
    val columnsWithDefault: Set<String> = emptySet(),
    /** Identifier quoting of the target DBMS. */
    val dialect: dev.teo.mydb.core.exec.SqlDialect = dev.teo.mydb.core.exec.SqlDialect.POSTGRES,
) {
    private fun quote(s: String) = dialect.quote(s)

    private val columns = page.columns.map { it.baseColumn?.takeIf(String::isNotEmpty) ?: it.label }
    val updates = linkedMapOf<Int, MutableMap<Int, Any?>>()
    val deletes = linkedSetOf<Int>()
    val inserts = mutableListOf<Array<Any?>>()

    val hasChanges get() = updates.isNotEmpty() || deletes.isNotEmpty() || inserts.isNotEmpty()

    fun setValue(row: Int, column: Int, value: Any?) {
        val original = page.rows[row][column]
        val cell = updates.getOrPut(row) { linkedMapOf() }
        if (value == original || (value is UserText && value.text == original?.toString())) cell.remove(column) else cell[column] = value
        if (cell.isEmpty()) updates.remove(row)
    }

    fun valueAt(row: Int, column: Int): Any? = updates[row]?.get(column) ?: page.rows[row][column]
    fun isModified(row: Int, column: Int) = updates[row]?.containsKey(column) == true

    fun deleteRow(row: Int) { deletes += row; updates.remove(row) }

    /** New row: columns with a default get [DefaultValue], the rest null. */
    fun addRow(): Int {
        inserts += Array(columns.size) { i -> if (columns[i] in columnsWithDefault) DefaultValue else null }
        return inserts.lastIndex
    }

    fun setInsertedValue(insert: Int, column: Int, value: Any?) { inserts[insert][column] = value }

    fun revertAll() { updates.clear(); deletes.clear(); inserts.clear() }

    fun revertRow(row: Int) { updates.remove(row); deletes.remove(row) }

    /** Statements in submission order: deletes, updates, inserts. */
    fun statements(): List<DmlStatement> = buildList {
        val target = "${quote(schema)}.${quote(table)}"
        for (row in deletes) {
            val (where, params) = whereClause(row)
            add(DmlStatement(DmlKind.DELETE, "DELETE FROM $target WHERE ${where.joinToString(" AND ")}", params,
                "DELETE\nFROM $target\nWHERE ${where.withLiterals(params).joinToString("\n  AND ")};", row))
        }
        for ((row, cells) in updates) {
            if (row in deletes) continue
            val sets = cells.keys.map { "${quote(columns[it])} = ?" }
            val setParams = cells.values.toList()
            val (where, whereParams) = whereClause(row)
            add(DmlStatement(DmlKind.UPDATE, "UPDATE $target SET ${sets.joinToString(", ")} WHERE ${where.joinToString(" AND ")}",
                setParams + whereParams,
                "UPDATE $target\nSET ${sets.withLiterals(setParams).joinToString(",\n    ")}\nWHERE ${where.withLiterals(whereParams).joinToString("\n  AND ")};", row))
        }
        for ((i, values) in inserts.withIndex()) {
            // SQLite has no DEFAULT in VALUES: columns left at their default are not listed
            val listed = if (dialect == dev.teo.mydb.core.exec.SqlDialect.SQLITE) columns.indices.filter { values[it] != DefaultValue } else columns.indices.toList()
            if (listed.isEmpty()) {
                add(DmlStatement(DmlKind.INSERT, "INSERT INTO $target DEFAULT VALUES", emptyList(), "INSERT INTO $target DEFAULT VALUES;", page.rows.size + i))
                continue
            }
            val cols = listed.joinToString(", ") { quote(columns[it]) }
            val placeholders = listed.map { if (values[it] == DefaultValue) "DEFAULT" else "?" }
            val params = listed.map { values[it] }.filter { it != DefaultValue }
            add(DmlStatement(DmlKind.INSERT, "INSERT INTO $target ($cols) VALUES (${placeholders.joinToString(", ")})", params,
                "INSERT INTO $target ($cols)\nVALUES (${listed.joinToString(", ") { if (values[it] == DefaultValue) "DEFAULT" else literal(values[it]) }});",
                page.rows.size + i))
        }
    }

    fun previewText(): String = statements().joinToString("\n\n") { it.display }

    /**
     * Sends all statements in one transaction. Any UPDATE/DELETE that does not
     * affect exactly one row aborts the whole submission.
     */
    fun submit(session: Session): SubmitResult {
        val stmts = statements()
        return try {
            session.inTransaction { c -> stmts.forEach { s -> s.execute(c) } }
            revertAll()
            SubmitResult.Ok(stmts.size)
        } catch (e: UnexpectedUpdateCount) {
            SubmitResult.Failed(e.statement, e.message!!, null)
        } catch (e: SQLException) {
            SubmitResult.Failed(stmts.firstOrNull { it.failedWith === e } ?: stmts.first(), e.message ?: e.toString(), e.sqlState)
        }
    }

    private fun whereClause(row: Int): Pair<List<String>, List<Any?>> {
        val original = page.rows[row]
        val keyIdx = if (keyColumns.isNotEmpty()) keyColumns.map { columns.indexOf(it) } else columns.indices.toList()
        val parts = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        for (i in keyIdx) {
            val v = original[i]
            if (v == null) parts += "${quote(columns[i])} IS NULL" else { parts += "${quote(columns[i])} = ?"; params += v }
        }
        return parts to params
    }

    private fun List<String>.withLiterals(params: List<Any?>): List<String> {
        val it = params.iterator()
        return map { part -> if (part.endsWith("= ?")) part.removeSuffix("?") + literal(it.next()) else part }
    }

    companion object {
        fun literal(v: Any?): String = when (v) {
            null -> "NULL"
            DefaultValue -> "DEFAULT"
            is UserText -> v.text.toDoubleOrNull()?.let { v.text } ?: ("'" + v.text.replace("'", "''") + "'")
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> "'" + v.toString().replace("'", "''") + "'"
        }
    }
}

enum class DmlKind { INSERT, UPDATE, DELETE }

/**
 * One generated DML statement. [row] is the grid row it came from: a page row for UPDATE/DELETE,
 * `page.rows.size + n` for the n-th inserted row (the grid shows inserted rows after the page).
 */
data class DmlStatement(val kind: DmlKind, val sql: String, val params: List<Any?>, val display: String, val row: Int = -1) {
    @Volatile internal var failedWith: SQLException? = null

    internal fun execute(c: Connection) {
        try {
            // PostgreSQL infers the type of an untyped value; SQLite and MySQL convert text to the column type
            val untyped = dev.teo.mydb.core.exec.SqlDialect.of(c) == dev.teo.mydb.core.exec.SqlDialect.POSTGRES
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p ->
                    when (p) {
                        is UserText -> if (untyped) ps.setObject(i + 1, p.text, Types.OTHER) else ps.setString(i + 1, p.text)
                        null -> ps.setNull(i + 1, Types.NULL)
                        else -> ps.setObject(i + 1, p)
                    }
                }
                val count = ps.executeUpdate()
                if (kind != DmlKind.INSERT && count != 1) throw UnexpectedUpdateCount(this, count, 1)
            }
        } catch (e: SQLException) {
            failedWith = e
            throw e
        }
    }
}

class UnexpectedUpdateCount(val statement: DmlStatement, val actual: Int, val expected: Int) :
    RuntimeException("Unexpected update count received (actual: $actual, expected: $expected)")

sealed interface SubmitResult {
    data class Ok(val statements: Int) : SubmitResult
    data class Failed(val statement: DmlStatement, val message: String, val sqlState: String?) : SubmitResult
}
