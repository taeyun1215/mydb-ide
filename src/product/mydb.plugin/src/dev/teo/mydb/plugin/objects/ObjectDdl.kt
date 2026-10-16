package dev.teo.mydb.plugin.objects

import com.intellij.openapi.project.Project
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.console.OutputLog
import dev.teo.mydb.plugin.model.ModelService

/** Runs object DDL from the create/modify dialog. */
object ObjectDdl {
    fun sessionKey(config: DataSourceConfig) = "ddl:${config.id}"

    /**
     * Executes [statements] in order on the data source's DDL session, stopping at the first error,
     * then introspects only the edited table (old and new name). Blocking: call off the EDT.
     */
    fun execute(project: Project, config: DataSourceConfig, database: String, draft: TableDraft, statements: List<String>): String? {
        val tables = setOfNotNull(draft.original?.name, draft.name)
        return execute(project, config, database, draft.schema.name, statements, RefreshTarget.Tables(database, draft.schema.name, tables))
    }

    /** Runs [statements] (stopping at the first error) and refreshes [refresh]; returns the error, if any. Blocking. */
    fun execute(project: Project, config: DataSourceConfig, database: String, schema: String?, statements: List<String>, refresh: RefreshTarget): String? {
        val handle = ConsoleService.getInstance(project).session(sessionKey(config), config.name, config)
        val log = OutputLog.getInstance(project)
        var error: String? = null
        for (sql in statements) {
            log.statement(handle.title, listOfNotNull(database, schema).joinToString("."), sql.replace('\n', ' '))
            val r = handle.session.execute(sql)
            if (r is StatementResult.Error) { error = "[${r.sqlState ?: ""}] ${r.message}"; log.error(handle.title, error); break }
        }
        if (dev.teo.mydb.plugin.settings.MyDbSettings.get().refreshAfterDdl) ModelService.getInstance(project).refreshNow(config, refresh)
        return error
    }
}
