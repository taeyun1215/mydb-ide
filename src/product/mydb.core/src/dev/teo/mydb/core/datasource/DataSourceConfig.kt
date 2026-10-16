package dev.teo.mydb.core.datasource

import java.util.UUID

/** Transaction handling for a connection. */
enum class TxMode { AUTO, MANUAL }

enum class IsolationLevel(val sql: String?) {
    DATABASE_DEFAULT(null),
    READ_COMMITTED("READ COMMITTED"),
    REPEATABLE_READ("REPEATABLE READ"),
    SERIALIZABLE("SERIALIZABLE"),
}

enum class SslMode(val pgValue: String) {
    DISABLE("disable"), ALLOW("allow"), PREFER("prefer"), REQUIRE("require"), VERIFY_CA("verify-ca"), VERIFY_FULL("verify-full")
}

data class SslConfig(
    val enabled: Boolean = false,
    val mode: SslMode = SslMode.REQUIRE,
    val caFile: String? = null,
    val clientCertFile: String? = null,
    val clientKeyFile: String? = null,
    /** Where server certificates are checked against: the CA file, the Java trust store or the OS store. */
    val trustStore: TrustStore = TrustStore.CA_FILE,
)

enum class TrustStore(val title: String) { CA_FILE("CA file"), JAVA("Java trust store"), SYSTEM("System trust store") }

enum class SshAuth { PASSWORD, KEY_PAIR }

/**
 * SSH tunnel of a data source. The password or key passphrase is a secret kept in the
 * IDE password store, not here.
 */
data class SshTunnelConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val auth: SshAuth = SshAuth.PASSWORD,
    val privateKeyFile: String? = null,
    /** Local port of the forward; 0 picks a free one. */
    val localPort: Int = 0,
    /** IDE-wide SSH configuration used instead of the fields above. */
    val configId: String? = null,
)

/**
 * Persistent description of a data source. Secrets are not part of this object;
 * they live in the IDE password store.
 */
data class DataSourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dbms: Dbms,
    val url: String,
    val user: String? = null,
    val txMode: TxMode = TxMode.AUTO,
    val readOnly: Boolean = false,
    val autoSync: Boolean = true,
    val ssl: SslConfig = SslConfig(),
    val ssh: SshTunnelConfig = SshTunnelConfig(),
    val driverProperties: Map<String, String> = emptyMap(),
    val introspectionScope: IntrospectionScope = IntrospectionScope.DEFAULT,
    /** Colour of the data source in the explorer and on its console tabs, `#rrggbb`. */
    val color: String? = null,
)

enum class Dbms(val displayName: String, val defaultPort: Int, val defaultDatabase: String?, val defaultUser: String?) {
    POSTGRES("PostgreSQL", 5432, "postgres", "postgres"),
    SQLITE("SQLite", 0, null, null),
    MYSQL("MySQL", 3306, null, "root"),
    /** SQL files read as a schema; no server. */
    DDL("DDL Files", 0, null, null),
}

/**
 * Which databases/schemas are introspected.
 * The default database's default schema is always included unless [includeDefaultSchema] is false.
 */
data class IntrospectionScope(
    val includeDefaultSchema: Boolean = true,
    val schemas: Set<SchemaRef> = emptySet(),
) {
    fun plus(ref: SchemaRef) = copy(schemas = schemas + ref)
    fun minus(ref: SchemaRef) = copy(schemas = schemas - ref)

    companion object {
        val DEFAULT = IntrospectionScope()
    }
}

data class SchemaRef(val database: String, val schema: String)
