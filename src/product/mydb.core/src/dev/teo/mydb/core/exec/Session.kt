package dev.teo.mydb.core.exec

import dev.teo.mydb.core.datasource.IsolationLevel
import dev.teo.mydb.core.datasource.TxMode
import java.sql.Connection
import java.sql.ResultSet

/** Column of a result set, with its origin when the server can tell (used for editability). */
data class ResultColumn(
    val label: String,
    val typeName: String,
    val jdbcType: Int,
    val baseSchema: String?,
    val baseTable: String?,
    val baseColumn: String?,
    val nullable: Boolean,
    /** Declared precision and scale (numeric(10,2) → 10, 2); -1 when unknown. Used to show `2.50`, not `2.5`. */
    val precision: Int = -1,
    val scale: Int = -1,
)

data class ResultPage(
    val columns: List<ResultColumn>,
    val rows: List<Array<Any?>>,
    /** More rows exist beyond [rows]. */
    val hasMore: Boolean,
    val executionMillis: Long,
    val fetchMillis: Long,
) {
    /** Single base table if every column maps to the same table; null when the result is read-only. */
    val sourceTable: Pair<String, String>? get() {
        val sources = columns.map { it.baseSchema to it.baseTable }.toSet()
        if (columns.isEmpty() || sources.size != 1) return null
        val (s, t) = sources.single()
        return if (s.isNullOrEmpty() || t.isNullOrEmpty() || columns.any { it.baseColumn.isNullOrEmpty() }) null else s to t
    }
}

sealed interface StatementResult {
    val statement: String
    data class Rows(override val statement: String, val page: ResultPage) : StatementResult
    data class Update(override val statement: String, val count: Long, val millis: Long) : StatementResult
    data class Error(override val statement: String, val message: String, val sqlState: String?, val position: Int?) : StatementResult
}

/** Events reported to the output log and status UI. */
interface SessionListener {
    fun transactionStateChanged(open: Boolean) {}
    fun message(text: String) {}
}

/**
 * A dedicated JDBC session with
 * auto/manual transaction handling.
 */
class Session(val connection: Connection, txMode: TxMode = TxMode.AUTO) : AutoCloseable {
    private val listeners = mutableListOf<SessionListener>()
    var pageSize: Int = 500

    var txMode: TxMode = txMode
        set(value) {
            if (field == value) return
            if (value == TxMode.AUTO && transactionOpen) commit()
            connection.autoCommit = value == TxMode.AUTO
            field = value
            notify { message(if (value == TxMode.AUTO) "Auto-commit mode on" else "Manual transaction mode on") }
        }

    var isolation: IsolationLevel = IsolationLevel.DATABASE_DEFAULT
        set(value) {
            field = value
            // via JDBC so that no transaction is started by the change itself; applies to the next transaction
            connection.transactionIsolation = when (value) {
                IsolationLevel.DATABASE_DEFAULT, IsolationLevel.READ_COMMITTED -> java.sql.Connection.TRANSACTION_READ_COMMITTED
                IsolationLevel.REPEATABLE_READ -> java.sql.Connection.TRANSACTION_REPEATABLE_READ
                IsolationLevel.SERIALIZABLE -> java.sql.Connection.TRANSACTION_SERIALIZABLE
            }
            notify { message("Transaction isolation set to ${value.name.lowercase().replace('_', ' ')}") }
        }

    /** True between the first statement in manual mode and commit/rollback. */
    var transactionOpen: Boolean = false
        private set(value) {
            if (field != value) { field = value; notify { transactionStateChanged(value) } }
        }

    init {
        connection.autoCommit = txMode == TxMode.AUTO
    }

    fun addListener(l: SessionListener) { listeners += l }

    fun execute(sql: String, maxRows: Int = pageSize): StatementResult {
        val started = System.nanoTime()
        return try {
            connection.createStatement().use { st ->
                st.maxRows = maxRows + 1
                val hasResultSet = st.execute(sql)
                val execMillis = (System.nanoTime() - started) / 1_000_000
                if (!connection.autoCommit) transactionOpen = true
                if (hasResultSet) StatementResult.Rows(sql, readPage(st.resultSet, maxRows, execMillis))
                else StatementResult.Update(sql, st.largeUpdateCount.coerceAtLeast(0), execMillis)
            }
        } catch (e: java.sql.SQLException) {
            if (!connection.autoCommit) transactionOpen = true // the transaction is aborted but still open
            StatementResult.Error(sql, e.message ?: e.toString(), e.sqlState, (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.position)
        }
    }

    fun executeAll(statements: List<String>): List<StatementResult> = statements.map { execute(it) }

    fun commit() {
        connection.commit()
        transactionOpen = false
        notify { message("Transaction committed") }
    }

    fun rollback() {
        connection.rollback()
        transactionOpen = false
        notify { message("Transaction rolled back") }
    }

    /** Runs [block] atomically: inside the open transaction in manual mode, or in its own one in auto mode. */
    fun <T> inTransaction(block: (Connection) -> T): T {
        val wasAuto = connection.autoCommit
        if (wasAuto) connection.autoCommit = false
        val savepoint = if (!wasAuto) connection.setSavepoint() else null
        try {
            val result = block(connection)
            if (wasAuto) connection.commit() else transactionOpen = true
            return result
        } catch (e: Throwable) {
            if (savepoint != null) connection.rollback(savepoint) else connection.rollback()
            throw e
        } finally {
            if (wasAuto) connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    private fun notify(event: SessionListener.() -> Unit) = listeners.forEach(event)

    companion object {
        fun readPage(rs: ResultSet, maxRows: Int, execMillis: Long = 0): ResultPage {
            val fetchStarted = System.nanoTime()
            val md = rs.metaData
            val pgMd = runCatching { md.unwrap(org.postgresql.PGResultSetMetaData::class.java) }.getOrNull()
            // SQLite reports the origin table and column of plain column references
            val sqlite = pgMd == null && runCatching { rs.statement?.connection?.metaData?.databaseProductName == "SQLite" }.getOrDefault(false)
            // the driver gives the alias, not the origin column: a name that is not a column of the table stays unknown
            val tableColumns = HashMap<String, Set<String>>()
            fun columnsOf(table: String): Set<String> = tableColumns.getOrPut(table) {
                runCatching {
                    rs.statement.connection.createStatement().use { st ->
                        st.executeQuery("pragma table_info('" + table.replace("'", "''") + "')").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
                    }
                }.getOrDefault(emptySet())
            }
            // MySQL/MariaDB report the original schema (catalog), table and column of each result column
            val mysql = pgMd == null && !sqlite && runCatching { rs.statement?.connection?.let(SqlDialect::of) == SqlDialect.MYSQL }.getOrDefault(false)
            fun mysqlOrigin(i: Int): Triple<String?, String?, String?> =
                if (!mysql) Triple(null, null, null)
                else runCatching {
                    Triple(md.getCatalogName(i).ifEmpty { null }, md.getTableName(i).ifEmpty { null }, md.getColumnName(i).ifEmpty { null })
                }.getOrDefault(Triple(null, null, null))
            fun sqliteOrigin(i: Int): Pair<String?, String?> {
                if (!sqlite) return null to null
                val table = runCatching { md.getTableName(i) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null to null
                val name = md.getColumnName(i)
                return table to name.takeIf { it in columnsOf(table) }
            }
            val columns = (1..md.columnCount).map { i ->
                val origin = sqliteOrigin(i)
                val my = mysqlOrigin(i)
                ResultColumn(
                    label = md.getColumnLabel(i),
                    typeName = md.getColumnTypeName(i),
                    jdbcType = md.getColumnType(i),
                    baseSchema = pgMd?.getBaseSchemaName(i) ?: origin.first?.let { "main" } ?: my.first,
                    baseTable = pgMd?.getBaseTableName(i) ?: origin.first ?: my.second,
                    baseColumn = pgMd?.getBaseColumnName(i) ?: origin.second ?: my.third,
                    nullable = md.isNullable(i) != java.sql.ResultSetMetaData.columnNoNulls,
                    precision = runCatching { md.getPrecision(i) }.getOrDefault(-1),
                    scale = runCatching { md.getScale(i) }.getOrDefault(-1),
                )
            }
            val rows = mutableListOf<Array<Any?>>()
            var more = false
            while (rs.next()) {
                if (rows.size == maxRows) { more = true; break }
                rows += Array(columns.size) { rs.getObject(it + 1) }
            }
            return ResultPage(columns, rows, more, execMillis, (System.nanoTime() - fetchStarted) / 1_000_000)
        }
    }
}
