package dev.teo.mydb.plugin

import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.core.model.DbModel
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.DriverManager

/** The test seed (testdb/seed) in a PostgreSQL 17 container, shared by all plugin tests of one JVM. */
object TestDatabase {
    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17").withDatabaseName("obs").withUsername("obs").withPassword("obs").also { c ->
            File(System.getProperty("mydb.seedDir")).listFiles { f -> f.extension == "sql" }!!.sorted().forEach {
                c.withCopyFileToContainer(MountableFile.forHostPath(it.toPath()), "/docker-entrypoint-initdb.d/${it.name}")
            }
            c.start()
        }
    }

    const val PASSWORD = "obs"
    private var counter = 0

    fun config(database: String = "obs", id: String = "test-ds-$database") = DataSourceConfig(
        id = id, name = "obs@localhost", dbms = Dbms.POSTGRES,
        url = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database", user = "obs",
        introspectionScope = dev.teo.mydb.core.datasource.IntrospectionScope().plus(SchemaRef(database, "shop")),
    )

    val model: DbModel by lazy { PostgresIntrospector().introspect(config(), PASSWORD) }

    @Synchronized
    fun freshConfig(): DataSourceConfig {
        val name = "p${++counter}_${ProcessHandle.current().pid()}"
        DriverManager.getConnection(container.jdbcUrl.replace("/obs", "/postgres"), "obs", "obs").use {
            it.createStatement().execute("create database $name template obs")
        }
        return config(name)
    }

    fun <T> query(config: DataSourceConfig, sql: String, read: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection(config.url, "obs", "obs").use { c -> c.createStatement().executeQuery(sql).use { rs -> rs.next(); read(rs) } }
}
