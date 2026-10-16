package dev.teo.mydb.plugin.util

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.plugin.model.ModelService
import javax.swing.Icon

/** What differs between the supported DBMS in the UI. */
object Dialects {
    fun icon(dbms: Dbms): Icon = when (dbms) {
        Dbms.POSTGRES -> AllIcons.Providers.Postgresql
        Dbms.SQLITE -> AllIcons.Providers.Sqlite
        Dbms.MYSQL -> AllIcons.Providers.Mysql
        Dbms.DDL -> AllIcons.FileTypes.Text
    }

    /** No server behind it: consoles, data editing and sessions are off. */
    fun hasConnection(config: DataSourceConfig?) = config?.dbms != Dbms.DDL

    /** DBMS offered in the "+" popups. */
    val supported = listOf(Dbms.POSTGRES, Dbms.MYSQL, Dbms.SQLITE, Dbms.DDL)

    /** Database name under which the introspection scope of [config] lists schemas (`def` for MySQL). */
    fun scopeDatabase(config: DataSourceConfig, urlDatabase: String?): String =
        if (config.dbms == Dbms.MYSQL) dev.teo.mydb.core.introspect.MysqlIntrospector.CATALOG else urlDatabase ?: "postgres"

    /** Features built on PostgreSQL DDL (object dialogs, schema compare, SQL generator, pg_dump). */
    fun hasPostgresDdl(config: DataSourceConfig?) = config?.dbms == Dbms.POSTGRES || config?.dbms == Dbms.DDL

    /** DDL of a table or view: generated for PostgreSQL, the stored text for SQLite. */
    fun ddl(project: Project, config: DataSourceConfig, database: String, schema: String, table: DbTable, options: DdlOptions = DdlOptions(contextSchema = schema)): String {
        if (config.dbms != Dbms.POSTGRES && config.dbms != Dbms.DDL) return table.sourceDdl ?: "-- no stored DDL for ${table.name}"
        val model = ModelService.getInstance(project).model(config.id)
        val gen = PgDdlGenerator(options) { s, t -> model?.schema(database, s)?.table(t) }
        return if (table.isView) gen.view(schema, table) else gen.table(schema, table)
    }
}
