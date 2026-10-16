package dev.teo.mydb.plugin

import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Light test case with the seeded data source registered and its model loaded. */
abstract class MyDbTestCase : BasePlatformTestCase() {
    protected lateinit var dataSource: DataSourceConfig
    private val extraDataSources = mutableListOf<DataSourceConfig>()

    override fun setUp() {
        super.setUp()
        dataSource = TestDatabase.config()
        DataSourceStorage.getInstance(project).save(dataSource)
        DataSourceStorage.getInstance(project).setPassword(dataSource, TestDatabase.PASSWORD)
        ModelService.getInstance(project).put(TestDatabase.model)
    }

    override fun tearDown() {
        try {
            (extraDataSources + dataSource).forEach {
                DataSourceStorage.getInstance(project).remove(it.id)
                ModelService.getInstance(project).remove(it.id)
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }

    /** Creates a console-like SQL file bound to the data source; `<caret>` marks the caret. */
    protected fun sql(text: String, schema: String? = null): PsiFile {
        val file = myFixture.configureByText("console.sql", text)
        SqlDataSourceBinding.getInstance(project).bind(file.virtualFile, dataSource.id, schema)
        return file
    }

    /** A registered data source on its own copy of the seed, for tests that change data or schema. */
    protected fun freshDataSource(id: String, setupSql: String? = null, extraSchemas: List<String> = emptyList()): DataSourceConfig {
        val base = TestDatabase.freshConfig()
        val db = base.url.substringAfterLast('/')
        val config = base.copy(id = id, introspectionScope = extraSchemas.fold(base.introspectionScope) { scope, s -> scope.plus(dev.teo.mydb.core.datasource.SchemaRef(db, s)) })
        setupSql?.let { sql -> java.sql.DriverManager.getConnection(config.url, "obs", TestDatabase.PASSWORD).use { it.createStatement().execute(sql) } }
        extraDataSources += config
        DataSourceStorage.getInstance(project).save(config)
        DataSourceStorage.getInstance(project).setPassword(config, TestDatabase.PASSWORD)
        ModelService.getInstance(project).put(PostgresIntrospector().introspect(config, TestDatabase.PASSWORD))
        return config
    }

    /** Pumps the EDT until [condition] holds (background JDBC work finishes via invokeLater). */
    protected fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }
}
