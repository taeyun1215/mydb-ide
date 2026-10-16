package dev.teo.mydb.core.datasource

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Import of another IDE project's data sources (synthetic files, no real hosts). */
class IdeDataSourceImportTest {
    @Test
    fun `data sources, users, ssh and scope are read, secrets stay in the keychain`() {
        val dir = Files.createTempDirectory("ide").toFile()
        java.io.File(dir, ".idea").mkdirs()
        java.io.File(dir, ".idea/dataSources.xml").writeText("""
            <project version="4"><component name="DataSourceManagerImpl" format="xml" multifile-model="true">
              <data-source source="LOCAL" name="App DB" uuid="11111111-2222-3333-4444-555555555555">
                <driver-ref>mysql_aurora.aws_wrapper</driver-ref><jdbc-url>jdbc:aws-wrapper:mysql://db.example:3306</jdbc-url>
              </data-source>
              <data-source source="LOCAL" name="Vector" uuid="aaaaaaaa-2222-3333-4444-555555555555">
                <jdbc-url>jdbc:postgresql://localhost:5433/appdb</jdbc-url>
              </data-source>
              <data-source source="LOCAL" name="Mongo" uuid="bbbbbbbb-2222-3333-4444-555555555555">
                <jdbc-url>mongodb://localhost:27017</jdbc-url>
              </data-source>
            </component></project>""".trimIndent())
        java.io.File(dir, ".idea/dataSources.local.xml").writeText("""
            <project version="4"><component name="dataSourceStorageLocal">
              <data-source name="App DB" uuid="11111111-2222-3333-4444-555555555555">
                <secret-storage>master_key</secret-storage><user-name>app</user-name>
                <schema-mapping><introspection-scope><node kind="schema" negative="1" /></introspection-scope></schema-mapping>
                <ssh-properties><enabled>true</enabled><ssh-config-id>ssh-1</ssh-config-id></ssh-properties>
              </data-source>
              <data-source name="Vector" uuid="aaaaaaaa-2222-3333-4444-555555555555">
                <user-name>postgres</user-name>
                <schema-mapping><introspection-scope><node kind="database" qname="appdb"><node kind="schema" qname="public" /><node kind="schema" qname="vec" /></node></introspection-scope></schema-mapping>
              </data-source>
            </component></project>""".trimIndent())
        val sshFile = java.io.File(dir, "sshConfigs.xml").apply { writeText("""
            <application><component name="SshConfigs"><configs>
              <sshConfig host="bastion.example" id="ssh-1" keyPath="/keys/id" port="2222" nameFormat="DESCRIPTIVE" username="ec2-user" />
            </configs></component></application>""".trimIndent()) }

        val all = IdeDataSourceImport.read(dir, IdeDataSourceImport.sshConfigs(sshFile))
        assertEquals(listOf("App DB", "Vector", "Mongo"), all.map { it.name })
        val app = all[0].config!!
        assertEquals(Dbms.MYSQL, app.dbms)
        assertEquals("jdbc:mysql://db.example:3306", app.url)
        assertEquals("app", app.user)
        assertTrue(app.ssh.enabled)
        assertEquals("bastion.example", app.ssh.host); assertEquals(2222, app.ssh.port); assertEquals(SshAuth.KEY_PAIR, app.ssh.auth)
        assertEquals("/keys/id", app.ssh.privateKeyFile)
        assertTrue(app.introspectionScope.schemas.isEmpty(), "all schemas")
        assertEquals("IntelliJ Platform DB — 11111111-2222-3333-4444-555555555555", all[0].keychainService)
        val vec = all[1].config!!
        assertEquals(setOf(SchemaRef("appdb", "public"), SchemaRef("appdb", "vec")), vec.introspectionScope.schemas)
        assertNull(all[2].config)
        assertTrue(all[2].problem!!.startsWith("unsupported"))
    }
}
