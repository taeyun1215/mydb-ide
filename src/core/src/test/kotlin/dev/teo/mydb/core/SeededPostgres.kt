package dev.teo.mydb.core

import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * One PostgreSQL 17 container per test JVM, seeded with the same script used for the
 * test seed (testdb/seed). Tests that change data use [freshDatabase].
 */
object SeededPostgres {
    private val seedDir = File(System.getProperty("mydb.seedDir") ?: "../../testdb/seed")

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17")
            .withDatabaseName("obs").withUsername("obs").withPassword("obs")
            .withCommand("postgres", "-c", "log_statement=all")
            .also { c ->
                seedDir.listFiles { f -> f.extension == "sql" }!!.sorted().forEach { f ->
                    c.withCopyFileToContainer(MountableFile.forHostPath(f.toPath()), "/docker-entrypoint-initdb.d/${f.name}")
                }
                c.start()
            }
    }

    private var counter = 0

    /** A new database cloned from the seeded one, so a test can modify data freely. */
    @Synchronized
    fun freshDatabase(): DataSourceConfig {
        val name = "t${++counter}_${ProcessHandle.current().pid()}"
        admin { it.createStatement().execute("create database $name template obs") }
        return config(name)
    }

    fun config(database: String = "obs") = DataSourceConfig(
        name = "obs@localhost",
        dbms = Dbms.POSTGRES,
        url = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database",
        user = "obs",
    )

    const val PASSWORD = "obs"

    fun <T> admin(block: (Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl.replace("/obs", "/postgres"), "obs", "obs").use(block)

    fun <T> connect(config: DataSourceConfig, block: (Connection) -> T): T =
        DriverManager.getConnection(config.url, "obs", "obs").use(block)

    /** Server-side statement log lines (log_statement=all) produced while [block] runs. */
    fun <T> captureStatements(block: () -> T): Pair<T, List<String>> {
        val before = container.logs.length
        val result = block()
        Thread.sleep(200)
        val stmt = Regex("""LOG:\s+(?:statement|execute [^:]*): (.*)""")
        val lines = container.logs.substring(before).lines().mapNotNull { stmt.find(it)?.groupValues?.get(1)?.trim() }
        return result to lines
    }
}
