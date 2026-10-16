package dev.teo.mydb.core.connection

import dev.teo.mydb.core.ProductInfo
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.datasource.SshTunnelConfig
import dev.teo.mydb.core.datasource.TrustStore
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties

/**
 * Opens JDBC connections for a data source. Drivers are resolved through [DriverRegistry]; an SSH
 * tunnel is opened first when the data source uses one, with its secret from [sshSecret]
 * host keys checked against [knownHosts].
 */
class Connector(
    private val drivers: DriverRegistry = DriverRegistry.DEFAULT,
    private val sshSecret: (DataSourceConfig) -> String? = { null },
    private val knownHosts: java.nio.file.Path = java.nio.file.Path.of(System.getProperty("user.home"), ".mydb", "known_hosts"),
    /** Password of an encrypted client key. */
    private val sslKeyPassword: (DataSourceConfig) -> String? = { null },
    /** Replaces a reference to a shared SSH configuration by its settings. */
    private val resolveSsh: (SshTunnelConfig) -> SshTunnelConfig = { it },
) {

    fun open(config: DataSourceConfig, password: String?, purpose: SessionPurpose = SessionPurpose.CONSOLE): Connection {
        if (!config.ssh.enabled) return openDirect(config, password)
        val parts = dev.teo.mydb.core.datasource.JdbcUrls.parse(config.dbms, config.url)
            ?: throw SQLException("SSH tunnel needs a host and port in the URL: ${config.url}")
        val tunnel = SshTunnel.open(resolveSsh(config.ssh), sshSecret(config), parts.host, parts.port ?: config.dbms.defaultPort, knownHosts)
        // only host:port changes; database and URL parameters are kept
        val local = config.copy(url = config.url.replaceFirst(Regex("//[^/:?]+(:\\d+)?"), "//127.0.0.1:${tunnel.localPort}"))
        val connection = try { openDirect(local, password) } catch (e: Exception) { tunnel.close(); throw e }
        return closingAlso(connection, tunnel)
    }

    /** The connection, closing [resource] with it. */
    private fun closingAlso(connection: Connection, resource: AutoCloseable): Connection =
        java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            } finally {
                if (method.name == "close") runCatching { resource.close() }
            }
        } as Connection

    private fun openDirect(config: DataSourceConfig, password: String?): Connection {
        val driver = drivers.driverFor(config.dbms)
        val props = Properties()
        config.user?.let { props["user"] = it }
        password?.let { props["password"] = it }
        if (config.dbms == Dbms.POSTGRES) {
            // the server sees which tool opened the session
            props["ApplicationName"] = ProductInfo.applicationName
            if (config.ssl.enabled) {
                props["ssl"] = "true"
                props["sslmode"] = config.ssl.mode.pgValue
                config.ssl.caFile?.let { props["sslrootcert"] = it }
                config.ssl.clientCertFile?.let { props["sslcert"] = it }
                config.ssl.clientKeyFile?.let { props["sslkey"] = it }
                sslKeyPassword(config)?.let { props["sslpassword"] = it }
                when (config.ssl.trustStore) {
                    TrustStore.CA_FILE -> {}
                    TrustStore.JAVA -> props["sslfactory"] = "org.postgresql.ssl.DefaultJavaSSLFactory"
                    TrustStore.SYSTEM -> props["sslfactory"] = SystemTrustSslFactory::class.java.name
                }
            } else {
                props["sslmode"] = "disable"
            }
        }
        if (config.dbms == Dbms.MYSQL) {
            // for MySQL: the session names the tool (shown in processlist / performance_schema)
            props["connectionAttributes"] = "program_name:" + ProductInfo.applicationName.replace(',', ' ')
            if (config.ssl.enabled) props["sslMode"] = when (config.ssl.mode.pgValue) { "verify-full" -> "verify-full"; "verify-ca" -> "verify-ca"; else -> "trust" }
        }
        if (config.dbms == Dbms.SQLITE) {
            // the read-only flag of a SQLite connection is fixed when the file is opened
            if (config.readOnly) props["open_mode"] = "1"
            props["foreign_keys"] = "true"
        }
        props.putAll(config.driverProperties)
        val connection = driver.connect(driverUrl(config), props)
            ?: throw SQLException("Driver ${driver.javaClass.name} does not accept URL ${config.url}")
        connection.autoCommit = true
        if (config.readOnly && config.dbms != Dbms.SQLITE) connection.isReadOnly = true
        return connection
    }
}

/** The MariaDB driver serves MySQL URLs only when they say so. */
internal fun driverUrl(config: DataSourceConfig): String =
    if (config.dbms == Dbms.MYSQL && config.url.startsWith("jdbc:mysql:") && "permitMysqlScheme" !in config.url)
        config.url + (if ('?' in config.url) "&" else "?") + "permitMysqlScheme"
    else config.url

enum class SessionPurpose { CONSOLE, TABLE_EDITOR, INTROSPECTION, IMPORT }

/** Maps a DBMS to an open-source JDBC driver available on the classpath. */
class DriverRegistry(private val factories: Map<Dbms, () -> Driver>) {
    fun driverFor(dbms: Dbms): Driver =
        factories[dbms]?.invoke() ?: throw SQLException("No driver configured for ${dbms.displayName}")

    companion object {
        val DEFAULT = DriverRegistry(
            mapOf(Dbms.POSTGRES to { org.postgresql.Driver() }, Dbms.SQLITE to { org.sqlite.JDBC() }, Dbms.MYSQL to { org.mariadb.jdbc.Driver() })
        )
    }
}
