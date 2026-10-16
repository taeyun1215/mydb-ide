package dev.teo.mydb.plugin.datasource

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.teo.mydb.core.datasource.SshAuth
import dev.teo.mydb.core.datasource.SshTunnelConfig
import java.util.UUID
import javax.swing.JComponent

/**
 * SSH configurations shared by data sources: host, port, user, authentication and key.
 * The password or passphrase of each lives in the IDE password store under the configuration id.
 */
@Service(Service.Level.APP)
@State(name = "MyDbSshConfigurations", storages = [Storage("mydb-ssh.xml")])
class SshConfigurations : PersistentStateComponent<SshConfigurations.State> {
    class State {
        @XCollection(style = XCollection.Style.v2)
        var entries: MutableList<Entry> = mutableListOf()
    }

    @Tag("ssh")
    class Entry {
        var id: String = UUID.randomUUID().toString()
        var name: String = ""
        var host: String = ""
        var port: Int = 22
        var user: String = ""
        var auth: String = SshAuth.PASSWORD.name
        var keyFile: String? = null

        fun label() = name.ifBlank { "$user@$host:$port" }
        fun copy() = Entry().also { it.id = id; it.name = name; it.host = host; it.port = port; it.user = user; it.auth = auth; it.keyFile = keyFile }
        /** The tunnel settings of a data source using this configuration; its local port stays its own. */
        fun applyTo(t: SshTunnelConfig) = t.copy(host = host, port = port, user = user, auth = SshAuth.valueOf(auth), privateKeyFile = keyFile)
    }

    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) { state = s }

    fun all(): List<Entry> = state.entries.map { it.copy() }
    fun find(id: String?): Entry? = id?.let { i -> state.entries.firstOrNull { it.id == i }?.copy() }
    fun replaceAll(entries: List<Entry>) {
        val gone = state.entries.map { it.id }.toSet() - entries.map { it.id }.toSet()
        gone.forEach { setSecret(it, null) }
        state.entries = entries.map { it.copy() }.toMutableList()
    }

    fun secret(id: String): String? = PasswordSafe.instance.getPassword(attributes(id))
    fun setSecret(id: String, secret: String?) = PasswordSafe.instance.set(attributes(id), secret?.let { Credentials("ssh", it) })
    private fun attributes(id: String) = CredentialAttributes(generateServiceName("MyDB SSH configuration", id))

    /** A data source's tunnel with a referenced configuration filled in. */
    fun resolve(t: SshTunnelConfig): SshTunnelConfig = find(t.configId)?.applyTo(t) ?: t

    companion object { fun getInstance(): SshConfigurations = service() }
}

/** Tools → SSH Configurations: list with add / edit / remove. */
class SshConfigurationsConfigurable : SearchableConfigurable {
    private val model = CollectionListModel<SshConfigurations.Entry>()
    private val secrets = HashMap<String, String?>()
    private var list: JBList<SshConfigurations.Entry>? = null

    override fun getId() = "mydb.ssh.configurations"
    override fun getDisplayName() = "SSH Configurations"

    override fun createComponent(): JComponent {
        reset()
        val l = JBList(model).apply {
            cellRenderer = com.intellij.ui.SimpleListCellRenderer.create("") { "${it.label()}   ${it.user}@${it.host}:${it.port}" }
        }
        list = l
        return ToolbarDecorator.createDecorator(l)
            .setAddAction { edit(SshConfigurations.Entry())?.let { model.add(it) } }
            .setEditAction { l.selectedValue?.let { cur -> edit(cur.copy())?.let { model.setElementAt(it, l.selectedIndex) } } }
            .setRemoveAction { l.selectedIndex.takeIf { it >= 0 }?.let { model.remove(it) } }
            .createPanel()
    }

    private fun edit(e: SshConfigurations.Entry): SshConfigurations.Entry? {
        val d = SshEntryDialog(e, secrets[e.id] ?: SshConfigurations.getInstance().secret(e.id))
        if (!d.showAndGet()) return null
        secrets[e.id] = d.secret
        return d.result()
    }

    override fun isModified() = model.items.map { listOf(it.id, it.name, it.host, it.port, it.user, it.auth, it.keyFile) } !=
        SshConfigurations.getInstance().all().map { listOf(it.id, it.name, it.host, it.port, it.user, it.auth, it.keyFile) } || secrets.isNotEmpty()

    override fun apply() {
        val store = SshConfigurations.getInstance()
        store.replaceAll(model.items)
        secrets.forEach { (id, s) -> store.setSecret(id, s) }
        secrets.clear()
    }

    override fun reset() {
        model.replaceAll(SshConfigurations.getInstance().all())
        secrets.clear()
    }
}

class SshEntryDialog(private val entry: SshConfigurations.Entry, secret: String?) : DialogWrapper(true) {
    private val name = JBTextField(entry.name)
    private val host = JBTextField(entry.host)
    private val port = JBTextField(entry.port.toString())
    private val user = JBTextField(entry.user)
    private val auth = ComboBox(SshAuth.entries.toTypedArray()).apply {
        selectedItem = SshAuth.valueOf(entry.auth)
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { if (it == SshAuth.PASSWORD) "Password" else "Key pair" }
    }
    private val key = JBTextField(entry.keyFile.orEmpty())
    private val secretField = JBPasswordField().apply { text = secret.orEmpty() }
    val secret: String? get() = String(secretField.password).ifEmpty { null }

    init { title = "SSH Configuration"; init() }

    fun result() = entry.copy().also {
        it.name = name.text.trim(); it.host = host.text.trim(); it.port = port.text.toIntOrNull() ?: 22; it.user = user.text.trim()
        it.auth = (auth.selectedItem as SshAuth).name; it.keyFile = key.text.trim().ifEmpty { null }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(name).align(AlignX.FILL) }
        row("Host:") { cell(host).align(AlignX.FILL).resizableColumn(); label("Port:"); cell(port).columns(6) }
        row("User:") { cell(user).align(AlignX.FILL) }
        row("Authentication:") { cell(auth) }
        row("Password / passphrase:") { cell(secretField).align(AlignX.FILL) }
        row("Private key:") { cell(key).align(AlignX.FILL) }
    }
}
