package dev.teo.mydb.core.connection

import dev.teo.mydb.core.datasource.SshAuth
import dev.teo.mydb.core.datasource.SshTunnelConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.sql.SQLException

/**
 * Local port forward through an SSH server to the database host: the JDBC connection
 * goes to `127.0.0.1:[localPort]`. Host keys are checked against [knownHosts]: a host seen for the
 * first time is recorded (trust on first use), a changed key is refused.
 */
class SshTunnel private constructor(private val client: SSHClient, private val server: ServerSocket, private val forwarder: Thread) : AutoCloseable {
    val localPort: Int get() = server.localPort

    override fun close() {
        runCatching { server.close() }
        runCatching { client.disconnect() }
        forwarder.interrupt()
    }

    companion object {
        /**
         * Closing a tunnel interrupts the copy threads; sshj reports that as errors, which the IDE shows as
         * "IDE error occurred". Real failures still reach the caller as exceptions.
         */
        private val quiet = listOf("net.schmizz.sshj.common.StreamCopier", "net.schmizz.concurrent.Promise")
            .map { java.util.logging.Logger.getLogger(it).apply { level = java.util.logging.Level.OFF } }

        fun open(config: SshTunnelConfig, secret: String?, targetHost: String, targetPort: Int, knownHosts: Path): SshTunnel {
            val client = SSHClient()
            try {
                Files.createDirectories(knownHosts.parent)
                if (!Files.exists(knownHosts)) Files.createFile(knownHosts)
                client.addHostKeyVerifier(TrustOnFirstUse(knownHosts.toFile()))
                client.connect(config.host, config.port)
                when (config.auth) {
                    SshAuth.PASSWORD -> client.authPassword(config.user, secret.orEmpty())
                    SshAuth.KEY_PAIR -> {
                        val file = config.privateKeyFile ?: throw SQLException("SSH: no private key file")
                        val keys = if (secret.isNullOrEmpty()) client.loadKeys(file) else client.loadKeys(file, secret)
                        client.authPublickey(config.user, keys)
                    }
                }
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), config.localPort))
                val params = Parameters("127.0.0.1", server.localPort, targetHost, targetPort)
                val thread = Thread({ runCatching { client.newLocalPortForwarder(params, server).listen() } }, "MyDB SSH tunnel ${config.host}")
                thread.isDaemon = true
                thread.start()
                return SshTunnel(client, server, thread)
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                throw SQLException("SSH tunnel to ${config.user}@${config.host}:${config.port} failed: ${e.message}", "08001", e)
            }
        }
    }

    /**
     * Records the key of a host seen for the first time; refuses a key that differs from a recorded
     * one, including a key of another type for a host that already has a recorded key.
     */
    private class TrustOnFirstUse(file: File) : OpenSSHKnownHosts(file) {
        override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
            if (entries().any { it.appliesTo(hostname) }) return false
            entries().add(OpenSSHKnownHosts.HostEntry(null, hostname, net.schmizz.sshj.common.KeyType.fromKey(key), key))
            write()
            return true
        }
        override fun hostKeyChangedAction(hostname: String, key: PublicKey) = false
    }
}
