package dev.teo.mydb.plugin.datasource

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.connection.ConnectionTestResult
import dev.teo.mydb.core.connection.ConnectionTester
import dev.teo.mydb.core.datasource.*
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.model.ModelService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel

/**
 * "Data Sources" dialog. Edits copies of the configurations; OK saves them,
 * opens a console for new data sources and starts the first introspection.
 */
/** Data sources dialog; [newDbms] starts it with a new, unsaved data source of that DBMS. */
class DataSourceDialog(private val project: Project, selectId: String?, newDbms: Dbms? = null) : DialogWrapper(project, true) {
    private val storage = DataSourceStorage.getInstance(project)
    private val originals = storage.all().associateBy { it.id }
    private val drafts = CollectionListModel(storage.all().map { Draft(it, storage.password(it.id), false).apply { sshSecret = storage.sshSecret(it.id); sslKeyPassword = storage.sslKeyPassword(it.id) } })
    private val list = JBList(drafts)
    private val editorHolder = JPanel(BorderLayout())
    private var current: DraftEditor? = null

    class Draft(var config: DataSourceConfig, var password: String?, val isNew: Boolean) {
        var nameEdited = !isNew
        /** SSH password or key passphrase. */
        var sshSecret: String? = null
        /** Password of the encrypted SSL client key. */
        var sslKeyPassword: String? = null
        override fun toString() = config.name
    }

    init {
        title = "Data Sources"
        list.cellRenderer = SimpleListCellRendererOf()
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        init()
        val index = drafts.items.indexOfFirst { it.config.id == selectId }.takeIf { it >= 0 } ?: 0
        if (drafts.size > 0) list.selectedIndex = index
        // an empty dialog starts with the DBMS choice, not a ready-made draft
        if (newDbms != null) addNew(newDbms)
        list.emptyText.text = "No data sources"
        list.emptyText.appendLine("Add a data source…", com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { chooseDbms(null) }
    }

    override fun createCenterPanel(): JComponent {
        val left = ToolbarDecorator.createDecorator(list)
            .setAddAction { b -> chooseDbms(b.preferredPopupPoint?.let { JBPopupFactory.getInstance().guessBestPopupLocation(list) }) }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
        val split = JPanel(BorderLayout())
        left.preferredSize = Dimension(JBUI.scale(220), 0)
        split.add(left, BorderLayout.WEST)
        editorHolder.border = JBUI.Borders.emptyLeft(8)
        split.add(editorHolder, BorderLayout.CENTER)
        split.preferredSize = Dimension(JBUI.scale(900), JBUI.scale(640))
        return split
    }

    /** "+" popup: DBMS list with speed search. */
    private fun chooseDbms(@Suppress("UNUSED_PARAMETER") at: Any?) {
        // DBMS entries, then "Template: <name>" entries
        val templates = DataSourceTemplates.getInstance().names()
        val items: List<Any> = dev.teo.mydb.plugin.util.Dialects.supported + templates.map { "Template: $it" }
        val step = object : BaseListPopupStep<Any>("Data Source", items) {
            override fun getTextFor(value: Any) = (value as? Dbms)?.displayName ?: value.toString()
            override fun getIconFor(value: Any): Icon = if (value is Dbms) dev.teo.mydb.plugin.util.Dialects.icon(value) else AllIcons.Nodes.Template
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: Any, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { if (selectedValue is Dbms) addNew(selectedValue) else addFromTemplate(selectedValue.toString().removePrefix("Template: ")) }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInCenterOf(list)
    }

    /** New data source from a template. */
    fun addFromTemplate(name: String) {
        val config = DataSourceTemplates.getInstance().create(name) ?: return
        drafts.add(Draft(config, null, true).apply { nameEdited = true })
        list.selectedIndex = drafts.size - 1
    }

    /** Current drafts, for tests. */
    fun drafts(): List<Draft> { current?.commit(); return drafts.items.toList() }

    private fun addNew(dbms: Dbms) {
        val f = ConnectionForm(dbms)
        val d = Draft(f.toConfig(), null, true)
        drafts.add(d)
        list.selectedIndex = drafts.size - 1
    }

    private fun removeSelected() {
        val i = list.selectedIndex.takeIf { it >= 0 } ?: return
        drafts.remove(i)
        if (drafts.size > 0) list.selectedIndex = minOf(i, drafts.size - 1) else editorHolder.removeAll()
    }

    private fun showSelected() {
        current?.commit()
        val d = list.selectedValue ?: return
        current = DraftEditor(d)
        editorHolder.removeAll()
        editorHolder.add(current!!.component, BorderLayout.CENTER)
        editorHolder.revalidate(); editorHolder.repaint()
    }

    override fun doOKAction() {
        apply()
        super.doOKAction()
    }

    /** Saves all drafts, refreshes changed data sources and opens consoles for new ones. */
    fun apply() {
        current?.commit()
        val keep = drafts.items.map { it.config.id }.toSet()
        originals.keys.filter { it !in keep }.forEach { storage.remove(it); ModelService.getInstance(project).remove(it) }
        for (d in drafts.items) {
            storage.save(d.config)
            storage.setPassword(d.config, d.password)
            storage.setSshSecret(d.config.id, d.sshSecret?.takeIf { d.config.ssh.enabled && d.config.ssh.configId == null })
            storage.setSslKeyPassword(d.config.id, d.sslKeyPassword?.takeIf { d.config.ssl.enabled })
            val changedConnection = originals[d.config.id]?.let { it.url != d.config.url || it.user != d.config.user || it.introspectionScope != d.config.introspectionScope } ?: true
            if (changedConnection) ModelService.getInstance(project).refresh(d.config) { m -> if (d.isNew) notifyLoaded(project, d.config, m) }
            if (d.isNew && dev.teo.mydb.plugin.settings.MyDbSettings.get().openConsoleOnCreate) ConsoleService.getInstance(project).openConsole(d.config)
        }
        // write .idea/mydbDataSources.xml right after a data source is created
        com.intellij.ide.SaveAndSyncHandler.getInstance().scheduleSave(com.intellij.ide.SaveAndSyncHandler.SaveTask(project, forceSavingAllSettings = true), forceExecuteImmediately = true)
    }

    /** After the first introspection of a new data source: what was loaded, with a link to choose schemas. */
    private fun notifyLoaded(project: Project, config: DataSourceConfig, m: dev.teo.mydb.core.model.DbModel) {
        val schemas = m.databases.flatMap { it.schemas }
        val text = "${schemas.size} schema(s), ${schemas.sumOf { it.tables.size }} table(s) and view(s) loaded"
        com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("MyDB")
            .createNotification(config.name, text, com.intellij.notification.NotificationType.INFORMATION)
            .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Choose schemas…") {
                DataSourceDialog(project, config.id).show()
            })
            .notify(project)
    }

    /** Form of one data source. */
    private inner class DraftEditor(val draft: Draft) {
        private val form = ConnectionForm(draft.config.dbms).apply {
            JdbcUrls.parse(draft.config.dbms, draft.config.url)?.let { p -> host = p.host; port = p.port ?: dbms.defaultPort; database = p.database }
            user = draft.config.user
            if (draft.nameEdited) editName(draft.config.name)
        }
        private val nameField = JBTextField(form.name)
        private val host = JBTextField(form.host)
        private val port = JBTextField(form.port.toString())
        private val user = JBTextField(form.user.orEmpty())
        private val password = JBPasswordField().apply { text = draft.password.orEmpty(); if (draft.password != null) emptyText.text = "<hidden>" }
        private val database = JBTextField(form.database.orEmpty())
        private val url = JBTextField(form.url)
        private val testResult = JBLabel()
        private val readOnly = JBCheckBox("Read-only", draft.config.readOnly)
        private val txMode = ComboBox(TxMode.entries.toTypedArray()).apply { selectedItem = draft.config.txMode }
        private val autoSync = JBCheckBox("Synchronize metadata automatically", draft.config.autoSync)
        private val sslEnabled = JBCheckBox("Use SSL", draft.config.ssl.enabled)
        private val sslMode = ComboBox(SslMode.entries.toTypedArray()).apply { selectedItem = draft.config.ssl.mode }
        private val sslCa = JBTextField(draft.config.ssl.caFile.orEmpty())
        private val sslCert = JBTextField(draft.config.ssl.clientCertFile.orEmpty())
        private val sslKey = JBTextField(draft.config.ssl.clientKeyFile.orEmpty())
        private val sslKeyPassword = JBPasswordField().apply { text = draft.sslKeyPassword.orEmpty() }
        val sslTrustStore = ComboBox(TrustStore.entries.toTypedArray()).apply {
            selectedItem = draft.config.ssl.trustStore
            renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.title }
        }
        /** Shared SSH configuration, or null for the fields of this data source. */
        val sshConfig = ComboBox((listOf<SshConfigurations.Entry?>(null) + SshConfigurations.getInstance().all()).toTypedArray()).apply {
            selectedItem = SshConfigurations.getInstance().all().firstOrNull { it.id == draft.config.ssh.configId }
            renderer = com.intellij.ui.SimpleListCellRenderer.create("<this data source>") { it?.label() ?: "<this data source>" }
            addActionListener { sshConfigChanged() }
        }
        private val sshEnabled = JBCheckBox("Use SSH tunnel", draft.config.ssh.enabled)
        private val sshHost = JBTextField(draft.config.ssh.host)
        private val sshPort = JBTextField(draft.config.ssh.port.toString())
        private val sshUser = JBTextField(draft.config.ssh.user)
        private val sshKey = JBTextField(draft.config.ssh.privateKeyFile.orEmpty())
        private val sshAuth = ComboBox(SshAuth.entries.toTypedArray()).apply {
            selectedItem = draft.config.ssh.auth
            renderer = com.intellij.ui.SimpleListCellRenderer.create("") { if (it == SshAuth.PASSWORD) "Password" else "Key pair" }
        }
        private val sshSecret = JBPasswordField().apply { text = draft.sshSecret.orEmpty() }
        private val sshLocalPort = JBTextField(draft.config.ssh.localPort.takeIf { it > 0 }?.toString().orEmpty(), 10).apply { emptyText.text = "<dynamic>" }
        private val propsModel = DriverPropertiesModel(draft.config)
        private val schemaModel = DefaultListModel<JCheckBox>()
        private var syncing = false
        private var schemaList: com.intellij.ui.CheckBoxList<String>? = null

        val component: JComponent

        init {
            fun onChange(field: JTextField, action: () -> Unit) = field.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) { if (!syncing) action() }
            })
            // fields → URL, URL → fields, user → automatic name
            onChange(host) { form.host = host.text; syncUrlAndName() }
            onChange(port) { port.text.toIntOrNull()?.let { form.port = it; syncUrlAndName() } }
            onChange(database) { form.database = database.text.ifEmpty { null }; syncUrlAndName() }
            onChange(user) { form.user = user.text; syncUrlAndName() }
            onChange(url) { form.editUrl(url.text); syncing = true; host.text = form.host; port.text = form.port.toString(); database.text = form.database.orEmpty(); syncing = false; syncName() }
            onChange(nameField) { form.editName(nameField.text); draft.nameEdited = true; draft.config = draft.config.copy(name = form.name); list.repaint() }

            // a SQLite data source is a file: path and URL only
            val file = com.intellij.openapi.ui.TextFieldWithBrowseButton(database).apply {
                addBrowseFolderListener(project, if (draft.config.dbms == Dbms.DDL) com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleFileOrDir().withTitle("DDL Files")
                    else com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleFile().withTitle("SQLite Database File"))
            }
            val general = if (draft.config.dbms == Dbms.SQLITE || draft.config.dbms == Dbms.DDL) panel {
                row(if (draft.config.dbms == Dbms.DDL) "SQL file or folder:" else "File:") { cell(file).align(AlignX.FILL) }
                row("URL:") { cell(url).align(AlignX.FILL) }
                row("") { comment("Editing the URL overrides the file above") }
                row { link("Test Connection") { testConnection() }; cell(testResult) }
                row { link("Save as Template…") { saveAsTemplate() } }
            } else panel {
                row("Host:") { cell(host).align(AlignX.FILL).resizableColumn(); label("Port:"); cell(port).columns(6) }
                row("User:") { cell(user).align(AlignX.FILL) }
                row("Password:") { cell(password).align(AlignX.FILL) }
                row("Database:") { cell(database).align(AlignX.FILL) }
                row("URL:") { cell(url).align(AlignX.FILL) }
                row("") { comment("Editing the URL overrides the fields above") }
                row { link("Test Connection") { testConnection() }; cell(testResult) }
                row { link("Save as Template…") { saveAsTemplate() } }
            }
            val options = panel {
                group("Connection") {
                    row { cell(readOnly) }
                    row("Transaction control:") { cell(txMode) }
                }
                group("Introspection") { row { cell(autoSync) } }
            }
            val sshSsl = panel {
                group("SSH") {
                    row { cell(sshEnabled) }
                    row("Configuration:") { cell(sshConfig) }
                    row("Host:") { cell(sshHost).align(AlignX.FILL).resizableColumn(); label("Port:"); cell(sshPort).columns(6) }
                    row("User:") { cell(sshUser).align(AlignX.FILL) }
                    row("Authentication:") { cell(sshAuth) }
                    row("Password / passphrase:") { cell(sshSecret).align(AlignX.FILL) }
                    row("Private key:") { cell(sshKey).align(AlignX.FILL) }
                    row("Local port:") { cell(sshLocalPort).columns(10) }
                }
                group("SSL") {
                    row { cell(sslEnabled) }
                    row("Mode:") { cell(sslMode) }
                    row("CA file:") { cell(sslCa).align(AlignX.FILL) }
                    row("Client certificate:") { cell(sslCert).align(AlignX.FILL) }
                    row("Client key:") { cell(sslKey).align(AlignX.FILL) }
                    row("Key password:") { cell(sslKeyPassword).align(AlignX.FILL) }
                    row("Trust store:") { cell(sslTrustStore) }
                }
            }
            val schemaList = com.intellij.ui.CheckBoxList<String>()
            val schemas = JPanel(BorderLayout()).apply {
                add(JBLabel("Schemas to introspect (the default schema is always included):"), BorderLayout.NORTH)
                add(JBScrollPane(schemaList), BorderLayout.CENTER)
                add(JButton("Load schema list").apply { addActionListener { loadSchemas(schemaList) } }, BorderLayout.SOUTH)
            }
            this.schemaList = schemaList
            draft.config.introspectionScope.schemas.forEach { schemaList.addItem(it.schema, it.schema, true) }
            val advanced = advancedTab()

            val tabs = JBTabbedPane()
            tabs.addTab("General", general)
            tabs.addTab("Options", options)
            if (draft.config.dbms != Dbms.SQLITE && draft.config.dbms != Dbms.DDL) tabs.addTab("SSH/SSL", sshSsl)
            tabs.addTab("Schemas", schemas)
            tabs.addTab("Advanced", advanced)
            component = JPanel(BorderLayout()).apply {
                add(panel { row("Name:") { cell(nameField).align(AlignX.FILL) } }, BorderLayout.NORTH)
                add(tabs, BorderLayout.CENTER)
            }
        }


        /** Driver properties: all properties by name, overrides in bold, description below. */
        private fun advancedTab(): JComponent {
            val table = JBTable(propsModel).apply {
                setDefaultRenderer(Any::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
                    override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): java.awt.Component {
                        val c = super.getTableCellRendererComponent(t, v, sel, focus, row, col)
                        font = if (propsModel.isChanged(row)) font.deriveFont(java.awt.Font.BOLD) else font.deriveFont(java.awt.Font.PLAIN)
                        return c
                    }
                })
            }
            val description = JBTextArea(3, 40).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            table.selectionModel.addListSelectionListener { description.text = propsModel.descriptionAt(table.selectedRow).orEmpty() }
            val decorated = ToolbarDecorator.createDecorator(table)
                .setAddAction { propsModel.addCustom() }
                .setRemoveAction { table.selectedRow.takeIf { it >= 0 }?.let(propsModel::reset) }
                .createPanel()
            return JPanel(BorderLayout()).apply {
                add(decorated, BorderLayout.CENTER)
                add(JBScrollPane(description), BorderLayout.SOUTH)
            }
        }

        private fun saveAsTemplate() {
            commit()
            val name = com.intellij.openapi.ui.Messages.showInputDialog(component, "Template name:", "Save as Template", null, draft.config.name, null) ?: return
            DataSourceTemplates.getInstance().save(name.trim().ifEmpty { return }, draft.config)
        }

        private fun syncUrlAndName() { syncing = true; url.text = form.url; syncing = false; syncName() }
        private fun syncName() {
            if (!form.nameEditedByUser) { syncing = true; nameField.text = form.name; syncing = false }
            draft.config = draft.config.copy(name = form.name) // the list shows the name being edited
            list.repaint()
        }

        private fun loadSchemas(target: com.intellij.ui.CheckBoxList<String>) {
            commit()
            val cfg = draft.config
            ApplicationManager.getApplication().executeOnPooledThread {
                val names = runCatching { dev.teo.mydb.core.introspect.Introspector.forDbms(cfg.dbms, DbConnector.get(project) { draft.sshSecret }).listSchemas(cfg, draft.password, form.database ?: "postgres") }.getOrDefault(emptyList())
                ApplicationManager.getApplication().invokeLater({
                    val checked = cfg.introspectionScope.schemas.map { it.schema }.toSet()
                    target.clear()
                    names.forEach { target.addItem(it, it, it in checked) }
                }, ModalityState.any())
            }
        }

        private fun testConnection() {
            commit()
            testResult.icon = AllIcons.Process.Step_1
            testResult.text = "Connecting…"
            val cfg = draft.config
            if (cfg.dbms == Dbms.DDL) {
                // no server: report what the files contain
                val path = JdbcUrls.parse(cfg.dbms, cfg.url)?.database.orEmpty()
                val files = dev.teo.mydb.core.introspect.DdlIntrospector().files(path)
                val tables = runCatching { dev.teo.mydb.core.introspect.DdlIntrospector().introspect(cfg, null).databases.sumOf { d -> d.schemas.sumOf { it.tables.size } } }.getOrDefault(0)
                testResult.icon = if (files.isEmpty()) AllIcons.General.Error else AllIcons.General.InspectionsOK
                testResult.text = if (files.isEmpty()) "No SQL files at $path" else "${files.size} file(s), $tables table(s) and view(s)"
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val r = ConnectionTester(DbConnector.get(project) { draft.sshSecret }).test(cfg, draft.password)
                ApplicationManager.getApplication().invokeLater({ showTest(r) }, ModalityState.any())
            }
        }

        /** Result summary. */
        private fun showTest(r: ConnectionTestResult) = when (r) {
            is ConnectionTestResult.Success -> {
                testResult.icon = AllIcons.General.InspectionsOK
                testResult.text = "<html>Succeeded — ${r.productName} ${r.productVersion}; identifiers ${r.plainIdentifierCase.name.lowercase()} / quoted ${r.quotedIdentifierCase.name.lowercase()}; " +
                    "${r.driverName} ${r.driverVersion} (JDBC ${r.jdbcVersion}); ping ${r.pingMillis} ms; SSL ${if (r.ssl) "yes" else "no"}</html>"
            }
            is ConnectionTestResult.Failure -> {
                testResult.icon = AllIcons.General.Error
                testResult.text = "<html>Failed${r.sqlState?.let { " [$it]" } ?: ""}: ${r.message}</html>"
            }
        }

        init { sshConfigChanged() }

        /** A shared SSH configuration fills and locks the tunnel fields. */
        private fun sshConfigChanged() {
            val e = sshConfig.selectedItem as? SshConfigurations.Entry
            listOf(sshHost, sshPort, sshUser, sshKey, sshSecret, sshAuth).forEach { it.isEnabled = e == null }
            if (e != null) {
                sshHost.text = e.host; sshPort.text = e.port.toString(); sshUser.text = e.user
                sshAuth.selectedItem = SshAuth.valueOf(e.auth); sshKey.text = e.keyFile.orEmpty()
            }
        }

        fun commit() {
            val scopeSchemas = schemaList?.let { l -> (0 until l.itemsCount).mapNotNull { i -> l.getItemAt(i)?.takeIf { l.isItemSelected(i) } } }
                ?: draft.config.introspectionScope.schemas.map { it.schema }
            val db = dev.teo.mydb.plugin.util.Dialects.scopeDatabase(draft.config, form.database)
            draft.config = draft.config.copy(
                name = form.name, url = form.url, user = form.user,
                readOnly = readOnly.isSelected, txMode = txMode.selectedItem as TxMode, autoSync = autoSync.isSelected,
                ssl = SslConfig(sslEnabled.isSelected, sslMode.selectedItem as SslMode, sslCa.text.ifBlank { null }, sslCert.text.ifBlank { null }, sslKey.text.ifBlank { null },
                    sslTrustStore.selectedItem as TrustStore),
                ssh = SshTunnelConfig(sshEnabled.isSelected, sshHost.text, sshPort.text.toIntOrNull() ?: 22, sshUser.text, sshAuth.selectedItem as SshAuth,
                    sshKey.text.ifBlank { null }, sshLocalPort.text.toIntOrNull() ?: 0, (sshConfig.selectedItem as? SshConfigurations.Entry)?.id),
                driverProperties = propsModel.overrides(),
                introspectionScope = IntrospectionScope(true, scopeSchemas.map { SchemaRef(db, it) }.toSet()),
            )
            draft.password = String(password.password).ifEmpty { draft.password }
            draft.sshSecret = String(sshSecret.password).ifEmpty { draft.sshSecret }
            draft.sslKeyPassword = String(sslKeyPassword.password).ifEmpty { draft.sslKeyPassword }
        }
    }

    private class SimpleListCellRendererOf : com.intellij.ui.ColoredListCellRenderer<Draft>() {
        override fun customizeCellRenderer(list: JList<out Draft>, value: Draft?, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = value?.config?.dbms?.let { dev.teo.mydb.plugin.util.Dialects.icon(it) } ?: AllIcons.Providers.Postgresql
            append(value?.config?.name.orEmpty())
        }
    }
}
