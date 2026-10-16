package dev.teo.mydb.plugin.datasource

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.teo.mydb.core.datasource.*

/**
 * Data sources of a project. Shared settings go to `.idea/mydbDataSources.xml` (commit-friendly),
 * user-specific ones (user name, introspection scope) to the workspace file, secrets to the IDE
 * password safe.
 */
@Service(Service.Level.PROJECT)
@State(name = "MyDbDataSources", storages = [Storage("mydbDataSources.xml")])
class DataSourceStorage(private val project: Project) : PersistentStateComponent<DataSourceStorage.SharedState> {

    class SharedState {
        @XCollection(style = XCollection.Style.v2)
        var dataSources: MutableList<SharedDataSource> = mutableListOf()
    }

    @Tag("data-source")
    class SharedDataSource {
        var id: String = ""
        var name: String = ""
        var dbms: String = Dbms.POSTGRES.name
        var url: String = ""
        var txMode: String = TxMode.AUTO.name
        var readOnly: Boolean = false
        var autoSync: Boolean = true
        var sslEnabled: Boolean = false
        var sslMode: String = SslMode.REQUIRE.name
        var sslCaFile: String? = null
        var sslClientCertFile: String? = null
        var sslClientKeyFile: String? = null
        var sshEnabled: Boolean = false
        var sshHost: String = ""
        var sshPort: Int = 22
        var sshUser: String = ""
        var sshKeyFile: String? = null
        var sshAuth: String = SshAuth.PASSWORD.name
        var sshLocalPort: Int = 0
        var driverProperties: MutableMap<String, String> = mutableMapOf()
        var color: String? = null
        var sslTrustStore: String = TrustStore.CA_FILE.name
        var sshConfigId: String? = null
    }

    private var shared = SharedState()

    override fun getState() = shared
    override fun loadState(state: SharedState) { shared = state }

    fun all(): List<DataSourceConfig> = shared.dataSources.map { s ->
        val local = project.service<DataSourceLocalStorage>().state(s.id)
        fromShared(s, local.user, IntrospectionScope(local.includeDefaultSchema, local.schemas.map {
            SchemaRef(it.substringBefore('/'), it.substringAfter('/'))
        }.toSet()))
    }

    fun find(id: String) = all().firstOrNull { it.id == id }

    fun save(config: DataSourceConfig) {
        val s = shared.dataSources.firstOrNull { it.id == config.id } ?: SharedDataSource().also { it.id = config.id; shared.dataSources += it }
        fillShared(s, config)
        project.service<DataSourceLocalStorage>().update(config)
        dev.teo.mydb.plugin.util.publishOnEdt(project) { project.messageBus.syncPublisher(TOPIC).dataSourcesChanged() }
    }

    fun remove(id: String) {
        shared.dataSources.removeIf { it.id == id }
        project.service<DataSourceLocalStorage>().remove(id)
        PasswordSafe.instance.set(credentialAttributes(id), null)
        PasswordSafe.instance.set(sshAttributes(id), null)
        dev.teo.mydb.plugin.util.publishOnEdt(project) { project.messageBus.syncPublisher(TOPIC).dataSourcesChanged() }
    }

    fun password(id: String): String? = PasswordSafe.instance.getPassword(credentialAttributes(id))

    fun setPassword(config: DataSourceConfig, password: String?) =
        PasswordSafe.instance.set(credentialAttributes(config.id), password?.let { Credentials(config.user, it) })

    /** SSH password or key passphrase of the data source's tunnel. */
    fun sshSecret(id: String): String? = PasswordSafe.instance.getPassword(sshAttributes(id))
    fun setSshSecret(id: String, secret: String?) = PasswordSafe.instance.set(sshAttributes(id), secret?.let { Credentials("ssh", it) })

    /** Password of an encrypted SSL client key. */
    fun sslKeyPassword(id: String): String? = PasswordSafe.instance.getPassword(sslKeyAttributes(id))
    fun setSslKeyPassword(id: String, secret: String?) = PasswordSafe.instance.set(sslKeyAttributes(id), secret?.let { Credentials("ssl-key", it) })
    private fun sslKeyAttributes(id: String) = CredentialAttributes(generateServiceName("MyDB SSL key", id))

    private fun credentialAttributes(id: String) = CredentialAttributes(generateServiceName("MyDB", id))
    private fun sshAttributes(id: String) = CredentialAttributes(generateServiceName("MyDB SSH", id))

    fun interface Listener { fun dataSourcesChanged() }

    companion object {
        fun fromShared(s: SharedDataSource, user: String?, scope: IntrospectionScope = IntrospectionScope.DEFAULT) = DataSourceConfig(
            id = s.id, name = s.name, dbms = Dbms.valueOf(s.dbms), url = s.url, user = user,
            txMode = TxMode.valueOf(s.txMode), readOnly = s.readOnly, autoSync = s.autoSync,
            ssl = SslConfig(s.sslEnabled, SslMode.valueOf(s.sslMode), s.sslCaFile, s.sslClientCertFile, s.sslClientKeyFile,
                runCatching { TrustStore.valueOf(s.sslTrustStore) }.getOrDefault(TrustStore.CA_FILE)),
            ssh = SshTunnelConfig(s.sshEnabled, s.sshHost, s.sshPort, s.sshUser, SshAuth.valueOf(s.sshAuth), s.sshKeyFile, s.sshLocalPort, s.sshConfigId),
            driverProperties = s.driverProperties.toMap(),
            introspectionScope = scope,
            color = s.color,
        )

        fun fillShared(s: SharedDataSource, config: DataSourceConfig) {
            s.name = config.name; s.dbms = config.dbms.name; s.url = config.url; s.txMode = config.txMode.name
            s.readOnly = config.readOnly; s.autoSync = config.autoSync
            s.sslEnabled = config.ssl.enabled; s.sslMode = config.ssl.mode.name; s.sslCaFile = config.ssl.caFile
            s.sslClientCertFile = config.ssl.clientCertFile; s.sslClientKeyFile = config.ssl.clientKeyFile
            s.sshEnabled = config.ssh.enabled; s.sshHost = config.ssh.host; s.sshPort = config.ssh.port; s.sshUser = config.ssh.user; s.sshKeyFile = config.ssh.privateKeyFile
            s.sshAuth = config.ssh.auth.name; s.sshLocalPort = config.ssh.localPort
            s.driverProperties = config.driverProperties.toMutableMap()
            s.color = config.color
            s.sslTrustStore = config.ssl.trustStore.name
            s.sshConfigId = config.ssh.configId
        }

        val TOPIC = Topic.create("MyDB data sources", Listener::class.java)
        fun getInstance(project: Project): DataSourceStorage = project.service()
    }
}

/** Per-user part of the data source settings (workspace file, not shared). */
@Service(Service.Level.PROJECT)
@State(name = "MyDbDataSourcesLocal", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DataSourceLocalStorage : PersistentStateComponent<DataSourceLocalStorage.LocalState> {

    class LocalState {
        @XCollection(style = XCollection.Style.v2)
        var entries: MutableList<Entry> = mutableListOf()
    }

    @Tag("data-source")
    class Entry {
        var id: String = ""
        var user: String? = null
        var includeDefaultSchema: Boolean = true
        @XCollection(style = XCollection.Style.v2, elementName = "schema")
        var schemas: MutableList<String> = mutableListOf()
    }

    private var state = LocalState()
    override fun getState() = state
    override fun loadState(s: LocalState) { state = s }

    fun state(id: String): Entry = state.entries.firstOrNull { it.id == id } ?: Entry().also { it.id = id }

    fun update(c: DataSourceConfig) {
        val e = state.entries.firstOrNull { it.id == c.id } ?: Entry().also { it.id = c.id; state.entries += it }
        e.user = c.user
        e.includeDefaultSchema = c.introspectionScope.includeDefaultSchema
        e.schemas = c.introspectionScope.schemas.map { "${it.database}/${it.schema}" }.sorted().toMutableList()
    }

    fun remove(id: String) { state.entries.removeIf { it.id == id } }
}
