package dev.teo.mydb.core.datasource

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Data sources of another IDE project (`.idea/dataSources.xml` and `.local.xml`) and the IDE's SSH
 * configurations, turned into MyDB data sources. Secrets are not in these files; the IDE keeps them in the
 * system keychain under [ImportedDataSource.keychainService].
 */
object IdeDataSourceImport {
    data class SshEntry(val id: String, val name: String, val host: String, val port: Int, val user: String, val keyFile: String?)

    data class ImportedDataSource(
        val name: String,
        val uuid: String,
        val config: DataSourceConfig?,
        /** Why it cannot be used, when [config] is null (unsupported DBMS). */
        val problem: String?,
        val ssh: SshEntry?,
        /** Keychain service of the IDE's password for this data source. */
        val keychainService: String,
    )

    private fun parse(file: File): Element? = file.takeIf { it.isFile }?.let {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(it).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    private fun Element.text(tag: String): String? = children(tag).firstOrNull()?.textContent?.trim()?.ifEmpty { null }
    private fun Element.allDataSources(): List<Element> = getElementsByTagName("data-source").let { l -> (0 until l.length).map { l.item(it) as Element } }

    /** SSH configurations of the IDE (`options/sshConfigs.xml`). */
    fun sshConfigs(file: File): Map<String, SshEntry> {
        val root = parse(file) ?: return emptyMap()
        val list = root.getElementsByTagName("sshConfig")
        return (0 until list.length).map { list.item(it) as Element }.associate { e ->
            val host = e.getAttribute("host"); val user = e.getAttribute("username")
            val custom = e.getAttribute("customName").ifEmpty { null }
            e.getAttribute("id") to SshEntry(e.getAttribute("id"), custom ?: "$user@$host", host, e.getAttribute("port").toIntOrNull() ?: 22, user,
                e.getAttribute("keyPath").ifEmpty { null }?.replace("\$USER_HOME\$", System.getProperty("user.home")))
        }
    }

    /** Data sources of the project at [projectDir], with SSH settings resolved from [ssh]. */
    fun read(projectDir: File, ssh: Map<String, SshEntry>): List<ImportedDataSource> {
        val shared = parse(File(projectDir, ".idea/dataSources.xml")) ?: return emptyList()
        val local = parse(File(projectDir, ".idea/dataSources.local.xml"))?.allDataSources()?.associateBy { it.getAttribute("uuid") }.orEmpty()
        return shared.allDataSources().map { ds ->
            val uuid = ds.getAttribute("uuid"); val name = ds.getAttribute("name")
            val loc = local[uuid]
            val url = (ds.text("jdbc-url") ?: "").replace("jdbc:aws-wrapper:", "jdbc:")
            val dbms = when {
                url.startsWith("jdbc:postgresql:") -> Dbms.POSTGRES
                url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:") -> Dbms.MYSQL
                url.startsWith("jdbc:sqlite:") -> Dbms.SQLITE
                else -> null
            }
            val sshProps = loc?.children("ssh-properties")?.firstOrNull()
            val sshEntry = sshProps?.takeIf { it.text("enabled") == "true" }?.text("ssh-config-id")?.let(ssh::get)
            val scope = loc?.let { scopeOf(it, dbms, url) } ?: IntrospectionScope.DEFAULT
            val config = dbms?.let {
                DataSourceConfig(
                    name = name, dbms = it, url = url.replace("jdbc:mariadb:", "jdbc:mysql:"), user = loc?.text("user-name"),
                    readOnly = ds.text("read-only") == "true" || loc?.text("read-only") == "true",
                    ssh = sshEntry?.let { s -> SshTunnelConfig(true, s.host, s.port, s.user, if (s.keyFile != null) SshAuth.KEY_PAIR else SshAuth.PASSWORD, s.keyFile, 0) }
                        ?: SshTunnelConfig(),
                    introspectionScope = scope,
                )
            }
            ImportedDataSource(name, uuid, config, if (dbms == null) "unsupported driver: ${url.substringBefore("//")}" else null, sshEntry,
                "IntelliJ Platform DB — $uuid")
        }
    }

    /**
     * Introspection scope: schemas named under `schema-mapping`; "all schemas" (a negative schema node) of a MySQL
     * server becomes an empty scope, which MyDB reads as every user schema.
     */
    private fun scopeOf(local: Element, dbms: Dbms?, url: String): IntrospectionScope {
        val scopeEl = local.getElementsByTagName("introspection-scope").item(0) as? Element ?: return IntrospectionScope.DEFAULT
        val refs = mutableSetOf<SchemaRef>()
        val urlDb = JdbcUrls.parse(dbms ?: return IntrospectionScope.DEFAULT, url)?.database
        fun walk(e: Element, database: String?) {
            for (n in e.children("node")) {
                val kind = n.getAttribute("kind"); val qname = n.getAttribute("qname").ifEmpty { n.getAttribute("name") }
                when (kind) {
                    "database" -> walk(n, qname.ifEmpty { database })
                    "schema" -> if (n.getAttribute("negative") != "1" && qname.isNotEmpty()) {
                        val db = if (dbms == Dbms.MYSQL) "def" else database ?: urlDb ?: "postgres"
                        refs += SchemaRef(db, qname)
                    }
                }
            }
        }
        walk(scopeEl, null)
        return IntrospectionScope(true, refs)
    }
}
