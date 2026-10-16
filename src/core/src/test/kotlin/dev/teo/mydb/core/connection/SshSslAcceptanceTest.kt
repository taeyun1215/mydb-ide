package dev.teo.mydb.core.connection

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.datasource.SshAuth
import dev.teo.mydb.core.datasource.SshTunnelConfig
import dev.teo.mydb.core.datasource.SslConfig
import dev.teo.mydb.core.datasource.SslMode
import net.schmizz.sshj.common.Buffer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SSH tunnel, SSL modes and driver properties against real servers. */
class SshSslAcceptanceTest {
    private val closeables = mutableListOf<AutoCloseable>()
    @AfterTest fun cleanup() = closeables.reversed().forEach { runCatching { it.close() } }
    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    private val sshPassword = "tunnel-secret"

    /** PostgreSQL reachable only inside a Docker network, plus an sshd on the same network. */
    private fun tunnelSetup(): Triple<GenericContainer<*>, PostgreSQLContainer, Network> {
        val network = Network.newNetwork().closing()
        val db = PostgreSQLContainer("postgres:17").withDatabaseName("obs").withUsername("obs").withPassword("obs")
            .withNetwork(network).withNetworkAliases("db").closing()
        db.start()
        val sshd = GenericContainer("testcontainers/sshd:1.3.0").withNetwork(network).withExposedPorts(22)
            // the image's default command sets the root password from these and starts sshd with forwarding on
            .withEnv("USERNAME", "root").withEnv("PASSWORD", sshPassword)
            .closing()
        sshd.start()
        return Triple(sshd, db, network)
    }

    private fun tunnelConfig(sshd: GenericContainer<*>) = DataSourceConfig(
        name = "via ssh", dbms = Dbms.POSTGRES, url = "jdbc:postgresql://db:5432/obs", user = "obs",
        ssh = SshTunnelConfig(enabled = true, host = sshd.host, port = sshd.getMappedPort(22), user = "root", auth = SshAuth.PASSWORD),
    )

    @Test
    fun `connection through an SSH tunnel comes from the sshd container`() {
        val (sshd, _, network) = tunnelSetup()
        val knownHosts = Files.createTempDirectory("mydb-ssh").resolve("known_hosts")
        val connector = Connector(sshSecret = { sshPassword }, knownHosts = knownHosts)
        val config = tunnelConfig(sshd)
        val sshdIp = sshd.containerInfo.networkSettings.networks.values.first { it.networkID == network.id }.ipAddress

        connector.open(config, "obs").use { c ->
            c.createStatement().executeQuery("select host(inet_client_addr())").use { rs -> rs.next(); assertEquals(sshdIp, rs.getString(1)) }
        }
        // first use recorded the host key; the next connection is verified against it
        assertTrue(Files.readString(knownHosts).contains("[${sshd.host}]:${sshd.getMappedPort(22)}"))
        connector.open(config, "obs").use { c -> assertTrue(c.isValid(2)) }
        val tester = ConnectionTester(connector).test(config, "obs")
        assertTrue(tester is ConnectionTestResult.Success, tester.toString())
    }

    @Test
    fun `changed SSH host key is refused`() {
        val (sshd, _, _) = tunnelSetup()
        val knownHosts = Files.createTempDirectory("mydb-ssh").resolve("known_hosts")
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val encoded = java.util.Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(other).compactData)
        // a different key is on record for this host (whatever key type the server negotiates)
        Files.writeString(knownHosts, "[${sshd.host}]:${sshd.getMappedPort(22)} ssh-rsa $encoded\n")
        val connector = Connector(sshSecret = { sshPassword }, knownHosts = knownHosts)
        val e = assertFailsWith<SQLException> { connector.open(tunnelConfig(sshd), "obs").close() }
        assertTrue(e.message!!.contains("SSH tunnel"), e.message)
    }

    @Test
    fun `wrong SSH password fails with a tunnel error`() {
        val (sshd, _, _) = tunnelSetup()
        val connector = Connector(sshSecret = { "wrong" }, knownHosts = Files.createTempDirectory("mydb-ssh").resolve("known_hosts"))
        val e = assertFailsWith<SQLException> { connector.open(tunnelConfig(sshd), "obs").close() }
        assertTrue(e.message!!.startsWith("SSH tunnel to root@"), e.message)
    }

    @Test
    fun `SSL require against a server without SSL fails with an SSL error`() {
        val config = SeededPostgres.config().copy(ssl = SslConfig(enabled = true, mode = SslMode.REQUIRE))
        val e = assertFailsWith<SQLException> { Connector().open(config, SeededPostgres.PASSWORD).close() }
        assertTrue(e.message!!.contains("SSL"), e.message)
    }

    @Test
    fun `SSL verify-full with the right CA succeeds`() {
        // the container creates a CA and a server certificate for localhost before PostgreSQL starts
        val script = """
            set -e; mkdir -p /certs; cd /certs
            openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=mydb-test-ca' -keyout ca.key -out ca.crt
            openssl req -newkey rsa:2048 -nodes -subj '/CN=localhost' -keyout server.key -out server.csr
            printf 'subjectAltName=DNS:localhost,IP:127.0.0.1' > san.ext
            openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 -extfile san.ext -out server.crt
            chown postgres:postgres server.key server.crt; chmod 600 server.key
            exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/certs/server.crt -c ssl_key_file=/certs/server.key
        """.trimIndent()
        val db = PostgreSQLContainer("postgres:17").withDatabaseName("obs").withUsername("obs").withPassword("obs")
            .withCreateContainerCmdModifier { it.withEntrypoint("bash", "-c", script).withCmd() }.closing()
        db.start()
        val ca = Files.createTempFile("mydb-ca", ".crt")
        db.copyFileFromContainer("/certs/ca.crt", ca.toString())
        val config = DataSourceConfig(
            name = "ssl", dbms = Dbms.POSTGRES, url = "jdbc:postgresql://localhost:${db.firstMappedPort}/obs", user = "obs",
            ssl = SslConfig(enabled = true, mode = SslMode.VERIFY_FULL, caFile = ca.toString()),
        )
        Connector().open(config, "obs").use { c ->
            c.createStatement().executeQuery("select ssl from pg_stat_ssl where pid = pg_backend_pid()").use { rs -> rs.next(); assertTrue(rs.getBoolean(1)) }
        }
        val result = ConnectionTester().test(config, "obs")
        assertTrue(result is ConnectionTestResult.Success && result.ssl, result.toString())
    }

    @Test
    fun `trust store choice decides which certificates are trusted`() {
        val script = """
            set -e; mkdir -p /certs; cd /certs
            openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=mydb-test-ca' -keyout ca.key -out ca.crt
            openssl req -newkey rsa:2048 -nodes -subj '/CN=localhost' -keyout server.key -out server.csr
            printf 'subjectAltName=DNS:localhost,IP:127.0.0.1' > san.ext
            openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 -extfile san.ext -out server.crt
            chown postgres:postgres server.key server.crt; chmod 600 server.key
            exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/certs/server.crt -c ssl_key_file=/certs/server.key
        """.trimIndent()
        val db = PostgreSQLContainer("postgres:17").withDatabaseName("obs").withUsername("obs").withPassword("obs")
            .withCreateContainerCmdModifier { it.withEntrypoint("bash", "-c", script).withCmd() }.closing()
        db.start()
        val base = DataSourceConfig(name = "ssl", dbms = Dbms.POSTGRES, url = "jdbc:postgresql://localhost:${db.firstMappedPort}/obs", user = "obs",
            ssl = SslConfig(enabled = true, mode = SslMode.REQUIRE))
        // the test CA is in neither the Java nor the system store: the handshake is refused
        for (store in listOf(dev.teo.mydb.core.datasource.TrustStore.JAVA, dev.teo.mydb.core.datasource.TrustStore.SYSTEM)) {
            val e = assertFailsWith<SQLException>(store.name) { Connector().open(base.copy(ssl = base.ssl.copy(trustStore = store)), "obs").close() }
            assertTrue(e.message!!.contains("SSL", ignoreCase = true) || e.cause is javax.net.ssl.SSLException, "$store: ${e.message}")
        }
        // CA file with mode require: encrypted without verification, as before
        Connector().open(base, "obs").use { c -> assertTrue(c.isValid(2)) }
    }

    @Test
    fun `a shared SSH configuration supplies the tunnel settings`() {
        val (sshd, _, _) = tunnelSetup()
        val shared = SshTunnelConfig(enabled = true, host = sshd.host, port = sshd.getMappedPort(22), user = "root", auth = SshAuth.PASSWORD)
        val connector = Connector(sshSecret = { sshPassword }, knownHosts = Files.createTempDirectory("mydb-ssh").resolve("known_hosts"),
            resolveSsh = { t -> if (t.configId == "cfg-1") shared.copy(localPort = t.localPort, configId = t.configId) else t })
        val config = DataSourceConfig(name = "via shared ssh", dbms = Dbms.POSTGRES, url = "jdbc:postgresql://db:5432/obs", user = "obs",
            ssh = SshTunnelConfig(enabled = true, configId = "cfg-1"))
        connector.open(config, "obs").use { c -> assertTrue(c.isValid(2)) }
    }

    @Test
    fun `driver properties list with overrides marked`() {
        val config = SeededPostgres.config().copy(driverProperties = mapOf("ApplicationName" to "custom"))
        val props = DriverProperties.describe(config)
        assertEquals(props.map { it.name.lowercase() }, props.map { it.name.lowercase() }.sorted())
        assertTrue(props.size > 50, "pgJDBC reports many properties: ${props.size}")
        val app = props.single { it.name == "ApplicationName" }
        assertTrue(app.changed)
        assertEquals("custom", app.value)
        assertTrue(props.filter { it.name != "ApplicationName" }.none { it.changed })
    }
}
