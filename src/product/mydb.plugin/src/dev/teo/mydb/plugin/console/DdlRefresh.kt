package dev.teo.mydb.plugin.console

import com.intellij.openapi.project.Project
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.exec.DdlStatements
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.core.model.DbModel
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.settings.MyDbSettings

/**
 * After DDL runs in a console or a script, the schemas it touched are introspected again, so that
 * highlighting, completion and the explorer see the new columns and tables.
 */
object DdlRefresh {

    /**
     * Schemas to introspect again after [statements] ran with [currentSchema] as the session schema;
     * empty when none of them is DDL.
     */
    fun targets(dbms: Dbms, model: DbModel?, database: String, currentSchema: String?, statements: List<String>): List<RefreshTarget> {
        val changes = statements.mapNotNull(DdlStatements::change)
        if (changes.isEmpty()) return emptyList()
        // only PostgreSQL introspects a single schema; the others always read everything
        if (dbms != Dbms.POSTGRES || changes.any { it.schemaList }) return listOf(RefreshTarget.All)
        val db = model?.database(database) ?: return listOf(RefreshTarget.All)
        val known = db.schemas.map { it.name }
        val names = linkedSetOf<String>()
        // unqualified objects are created in the session schema
        currentSchema?.let(names::add)
        for (s in changes.flatMap { it.schemas }) {
            (known.firstOrNull { it == s } ?: known.firstOrNull { it.equals(s, ignoreCase = true) })?.let(names::add)
        }
        if (names.isEmpty()) return listOf(RefreshTarget.All)
        return names.map { RefreshTarget.Schema(database, it) }
    }

    /**
     * Refreshes what the successfully [executed] statements changed. With a [handle] whose PostgreSQL / SQLite
     * transaction is still open the refresh waits for the commit: DDL is transactional there, and introspection
     * runs on its own connection, which does not see it before. MySQL commits DDL implicitly.
     */
    fun afterRun(project: Project, config: DataSourceConfig, handle: SessionHandle?, database: String, currentSchema: String?, executed: List<String>) {
        if (!MyDbSettings.get().refreshAfterDdl) return
        val targets = targets(config.dbms, ModelService.getInstance(project).rawModel(config.id), database, currentSchema, executed)
        if (targets.isEmpty()) return
        if (handle != null && config.dbms != Dbms.MYSQL && handle.session.transactionOpen) {
            handle.pendingRefresh.addAll(targets)
            return
        }
        queue(project, config, targets)
    }

    /** Queued from the EDT: a background task queued from a pooled thread waits for the EDT to start. */
    private fun queue(project: Project, config: DataSourceConfig, targets: List<RefreshTarget>) =
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            ModelService.getInstance(project).refresh(config, targets)
        }, project.disposed)

    /** The console's transaction ended: refresh what its DDL changed if committed, drop it if rolled back. */
    fun transactionEnded(project: Project, handle: SessionHandle, committed: Boolean) {
        val targets = handle.pendingRefresh.toList()
        handle.pendingRefresh.clear()
        if (committed && targets.isNotEmpty()) queue(project, handle.dataSource, targets)
    }
}
