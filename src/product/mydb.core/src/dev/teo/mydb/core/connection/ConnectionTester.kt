package dev.teo.mydb.core.connection

import dev.teo.mydb.core.datasource.DataSourceConfig
import java.sql.Connection

/** Result of "Test Connection". */
sealed interface ConnectionTestResult {
    data class Success(
        val productName: String,
        val productVersion: String,
        val plainIdentifierCase: IdentifierCase,
        val quotedIdentifierCase: IdentifierCase,
        val driverName: String,
        val driverVersion: String,
        val jdbcVersion: String,
        val pingMillis: Long,
        val ssl: Boolean,
    ) : ConnectionTestResult

    data class Failure(val message: String, val sqlState: String?) : ConnectionTestResult
}

enum class IdentifierCase { LOWER, UPPER, MIXED, EXACT }

class ConnectionTester(private val connector: Connector = Connector()) {

    fun test(config: DataSourceConfig, password: String?): ConnectionTestResult {
        return try {
            connector.open(config, password, SessionPurpose.INTROSPECTION).use { c ->
                val ping = pingMillis(c)
                val md = c.metaData
                ConnectionTestResult.Success(
                    productName = md.databaseProductName,
                    productVersion = md.databaseProductVersion,
                    plainIdentifierCase = when {
                        md.storesLowerCaseIdentifiers() -> IdentifierCase.LOWER
                        md.storesUpperCaseIdentifiers() -> IdentifierCase.UPPER
                        md.supportsMixedCaseIdentifiers() -> IdentifierCase.EXACT
                        else -> IdentifierCase.MIXED
                    },
                    quotedIdentifierCase = when {
                        md.supportsMixedCaseQuotedIdentifiers() -> IdentifierCase.EXACT
                        md.storesLowerCaseQuotedIdentifiers() -> IdentifierCase.LOWER
                        md.storesUpperCaseQuotedIdentifiers() -> IdentifierCase.UPPER
                        else -> IdentifierCase.MIXED
                    },
                    driverName = md.driverName,
                    driverVersion = md.driverVersion,
                    jdbcVersion = "${md.jdbcMajorVersion}.${md.jdbcMinorVersion}",
                    pingMillis = ping,
                    ssl = isSsl(c),
                )
            }
        } catch (e: java.sql.SQLException) {
            ConnectionTestResult.Failure(e.message ?: e.toString(), e.sqlState)
        }
    }

    private fun pingMillis(c: Connection): Long {
        val started = System.nanoTime()
        c.createStatement().use { it.execute("select 1") }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun isSsl(c: Connection): Boolean = runCatching {
        c.createStatement().use { st ->
            st.executeQuery("select ssl from pg_stat_ssl where pid = pg_backend_pid()").use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }.getOrDefault(false)
}
