package dev.teo.mydb.core.connection

import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * pgJDBC socket factory that trusts the operating system's certificate store: the macOS keychain,
 * the Windows root store, else the Java default.
 */
class SystemTrustSslFactory : org.postgresql.ssl.WrappedFactory() {
    init {
        val os = System.getProperty("os.name").lowercase()
        val store: KeyStore? = when {
            os.contains("mac") -> KeyStore.getInstance("KeychainStore").apply { load(null, null) }
            os.contains("win") -> KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
            else -> null // Java default trust store
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        factory = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }
}
