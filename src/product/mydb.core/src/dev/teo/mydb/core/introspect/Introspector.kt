package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.model.DbModel

/** Reads the metadata of a data source into a [DbModel]. */
interface Introspector {
    fun introspect(config: DataSourceConfig, password: String?, previous: DbModel? = null, target: RefreshTarget = RefreshTarget.All): DbModel

    /** Schema names for the scope chooser. */
    fun listSchemas(config: DataSourceConfig, password: String?, database: String): List<String>

    companion object {
        fun forDbms(dbms: Dbms, connector: Connector = Connector()): Introspector = when (dbms) {
            Dbms.SQLITE -> SqliteIntrospector(connector)
            Dbms.MYSQL -> MysqlIntrospector(connector)
            Dbms.DDL -> DdlIntrospector()
            else -> PostgresIntrospector(connector)
        }
    }
}
