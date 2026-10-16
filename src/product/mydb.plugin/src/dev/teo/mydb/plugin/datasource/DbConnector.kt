package dev.teo.mydb.plugin.datasource

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.introspect.PostgresIntrospector

/**
 * Connectors of a project: SSH secrets come from the password safe (or [sshSecret] for unsaved
 * settings in the data source dialog), host keys from `<config>/mydb/known_hosts`.
 */
object DbConnector {
    fun knownHosts() = PathManager.getConfigDir().resolve("mydb/known_hosts")

    /**
     * Connector with the project's secrets; a data source using a shared SSH configuration takes that
     * configuration's settings and secret.
     */
    fun get(project: Project, sshSecret: ((DataSourceConfig) -> String?)? = null, sslKeyPassword: ((DataSourceConfig) -> String?)? = null) =
        Connector(
            sshSecret = sshSecret ?: { c -> c.ssh.configId?.let { SshConfigurations.getInstance().secret(it) } ?: DataSourceStorage.getInstance(project).sshSecret(c.id) },
            knownHosts = knownHosts(),
            sslKeyPassword = sslKeyPassword ?: { DataSourceStorage.getInstance(project).sslKeyPassword(it.id) },
            resolveSsh = { SshConfigurations.getInstance().resolve(it) },
        )

    fun introspector(project: Project, dbms: dev.teo.mydb.core.datasource.Dbms = dev.teo.mydb.core.datasource.Dbms.POSTGRES) =
        dev.teo.mydb.core.introspect.Introspector.forDbms(dbms, get(project))
}
