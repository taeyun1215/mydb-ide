package dev.teo.mydb.plugin.datasource

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.datasource.SshAuth
import dev.teo.mydb.core.datasource.SshTunnelConfig
import dev.teo.mydb.core.datasource.SslConfig
import dev.teo.mydb.core.datasource.SslMode
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.console.ConsoleService

/** Data source settings: and */
class DataSourceSettingsTest : MyDbTestCase() {
    private val storage get() = DataSourceStorage.getInstance(project)

    fun `test settings files keep no secrets`() {
        val config = dataSource.copy(
            id = "secret-ds",
            ssl = SslConfig(true, SslMode.VERIFY_FULL, "/tmp/ca.crt", "/tmp/client.crt", "/tmp/client.key"),
            ssh = SshTunnelConfig(true, "bastion.example", 2222, "deploy", SshAuth.KEY_PAIR, "/tmp/id_ed25519", 15432),
        )
        storage.save(config)
        storage.setPassword(config, "db-password-123")
        storage.setSshSecret(config.id, "ssh-passphrase-456")
        try {
            val shared = JDOMUtil.write(XmlSerializer.serialize(storage.state))
            val local = JDOMUtil.write(XmlSerializer.serialize(project.service<DataSourceLocalStorage>().state))
            assertTrue(shared, shared.contains(config.url) && shared.contains("bastion.example"))
            for (xml in listOf(shared, local)) {
                assertFalse(xml.contains("db-password-123"))
                assertFalse(xml.contains("ssh-passphrase-456"))
            }
            assertEquals("db-password-123", storage.password(config.id))
            assertEquals("ssh-passphrase-456", storage.sshSecret(config.id))
            assertEquals(config.copy(introspectionScope = storage.find(config.id)!!.introspectionScope), storage.find(config.id))
        } finally { storage.remove(config.id) }
        assertNull("removing the data source forgets its secrets", storage.sshSecret(config.id))
    }

    fun `test OK on a new data source opens its console`() {
        val dialog = DataSourceDialog(project, null, Dbms.POSTGRES)
        val created = dialog.drafts().last()
        try {
            created.config = created.config.copy(url = dataSource.url, user = "obs", name = "new@test")
            created.password = TestDatabase.PASSWORD
            dialog.apply()
            val console = ConsoleService.getInstance(project).consoleDir(created.config.id).resolve("console.sql")
            assertTrue(FileEditorManager.getInstance(project).openFiles.any { it.toNioPath() == console })
            val vf = FileEditorManager.getInstance(project).openFiles.first { it.toNioPath() == console }
            assertEquals("console [${storage.find(created.config.id)!!.name}]", dev.teo.mydb.plugin.console.ConsoleTabTitleProvider().getEditorTabTitle(project, vf))
            // an empty dialog has no draft until a DBMS is chosen
            val empty = DataSourceDialog(project, null)
            try { assertEquals(storage.all().size, empty.drafts().size) } finally { empty.close(DialogWrapper.CANCEL_EXIT_CODE) }
            assertNotNull(storage.find(created.config.id))
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            storage.remove(created.config.id)
        }
    }

    fun `test a data source made from a template copies its settings`() {
        val templates = DataSourceTemplates.getInstance()
        val source = dataSource.copy(ssl = SslConfig(true, SslMode.REQUIRE), driverProperties = mapOf("connectTimeout" to "7"))
        templates.save("pg-template", source)
        try {
            val created = templates.create("pg-template")!!
            assertFalse(created.id == source.id)
            assertEquals("pg-template", created.name)
            assertEquals(source.url, created.url)
            assertEquals(source.user, created.user)
            assertEquals(source.ssl, created.ssl)
            assertEquals(source.driverProperties, created.driverProperties)

            val dialog = DataSourceDialog(project, dataSource.id)
            try {
                dialog.addFromTemplate("pg-template")
                val draft = dialog.drafts().last()
                assertTrue(draft.isNew)
                assertEquals(source.url, draft.config.url)
                assertEquals(source.driverProperties, draft.config.driverProperties)
            } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        } finally { templates.remove("pg-template") }
    }

    fun `test shared SSH configurations and SSL trust store are stored and resolved`() {
        val store = SshConfigurations.getInstance()
        val before = store.all()
        val entry = SshConfigurations.Entry().apply { name = "bastion"; host = "bastion.example"; port = 2222; user = "ops" }
        try {
            store.replaceAll(before + entry)
            store.setSecret(entry.id, "s3cret")
            val config = dev.teo.mydb.core.datasource.DataSourceConfig(name = "shared-ssh", dbms = dev.teo.mydb.core.datasource.Dbms.POSTGRES,
                url = "jdbc:postgresql://db:5432/obs", user = "obs",
                ssh = dev.teo.mydb.core.datasource.SshTunnelConfig(enabled = true, localPort = 15432, configId = entry.id),
                ssl = dev.teo.mydb.core.datasource.SslConfig(enabled = true, trustStore = dev.teo.mydb.core.datasource.TrustStore.SYSTEM))
            val storage = DataSourceStorage.getInstance(project)
            storage.save(config)
            try {
                val loaded = storage.find(config.id)!!
                assertEquals(entry.id, loaded.ssh.configId)
                assertEquals(dev.teo.mydb.core.datasource.TrustStore.SYSTEM, loaded.ssl.trustStore)
                val resolved = store.resolve(loaded.ssh)
                assertEquals(listOf("bastion.example", "2222", "ops", "15432"), listOf(resolved.host, resolved.port.toString(), resolved.user, resolved.localPort.toString()))
                assertEquals("s3cret", store.secret(entry.id))
                // the shared settings file has no secret
                val xml = com.intellij.util.xmlb.XmlSerializer.serialize(store.state).let { com.intellij.openapi.util.JDOMUtil.write(it) }
                assertFalse(xml, xml.contains("s3cret"))
            } finally { storage.remove(config.id) }
        } finally { store.replaceAll(before) }
        assertNull("secrets of removed configurations are deleted", store.secret(entry.id))
    }

    fun `test data sources copy to XML without secrets and paste as new ones`() {
        val xml = DataSourceTransfer.toXml(listOf(dataSource))
        assertFalse(xml, xml.contains(TestDatabase.PASSWORD + "<") || xml.contains("password"))
        assertTrue(xml, xml.contains(dataSource.url))
        val parsed = DataSourceTransfer.fromXml(xml)!!.single()
        assertEquals(dataSource.url, parsed.url)
        assertEquals(dataSource.user, parsed.user)
        assertNull(DataSourceTransfer.fromXml("<something/>"))
        val pasted = DataSourceTransfer.paste(project, listOf(parsed)).single()
        try {
            assertFalse(pasted.id == dataSource.id)
            assertEquals("${dataSource.name} (1)", pasted.name)
            assertNull(storage.password(pasted.id))
        } finally { storage.remove(pasted.id) }
    }

    fun `test import from an IDE project creates data sources, ssh configurations and passwords`() {
        val ssh = dev.teo.mydb.core.datasource.IdeDataSourceImport.SshEntry("ide-ssh-1", "ec2-user@bastion", "bastion.example", 22, "ec2-user", "/keys/id")
        val cfg = dev.teo.mydb.core.datasource.DataSourceConfig(name = "Imported DB", dbms = dev.teo.mydb.core.datasource.Dbms.MYSQL,
            url = "jdbc:mysql://db.example:3306", user = "app",
            ssh = dev.teo.mydb.core.datasource.SshTunnelConfig(true, "bastion.example", 22, "ec2-user", dev.teo.mydb.core.datasource.SshAuth.KEY_PAIR, "/keys/id"))
        val item = dev.teo.mydb.core.datasource.IdeDataSourceImport.ImportedDataSource("Imported DB", "u-1", cfg, null, ssh, "svc-u-1")
        val sshStore = SshConfigurations.getInstance()
        val before = sshStore.all()
        val asked = mutableListOf<String>()
        try {
            val created = IdeImport.apply(project, listOf(item), copyPasswords = true) { asked += it; "secret-from-keychain" }.single()
            assertEquals(listOf("svc-u-1"), asked)
            assertEquals("ide-ssh-1", storage.find(created.id)!!.ssh.configId)
            assertEquals("bastion.example", sshStore.find("ide-ssh-1")!!.host)
            assertEquals("secret-from-keychain", storage.password(created.id))
            storage.setPassword(created, null); storage.remove(created.id)
        } finally { sshStore.replaceAll(before) }
    }

    fun `test advanced tab model marks overridden properties`() {
        val model = DriverPropertiesModel(dataSource.copy(driverProperties = mapOf("ApplicationName" to "custom")))
        val names = (0 until model.rowCount).map { model.getValueAt(it, 0) as String }
        val app = names.indexOf("ApplicationName")
        assertTrue(model.isChanged(app))
        assertEquals(mapOf("ApplicationName" to "custom"), model.overrides())
        val timeout = names.indexOf("connectTimeout")
        model.setValueAt("5", timeout, 1)
        assertTrue(model.isChanged(timeout))
        model.reset(app)
        assertEquals(mapOf("connectTimeout" to "5"), model.overrides())
    }
}
