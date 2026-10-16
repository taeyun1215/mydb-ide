package dev.teo.mydb.plugin.settings

import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ApplicationManager
import dev.teo.mydb.core.exec.CaretInsidePolicy
import dev.teo.mydb.core.exec.RunTarget
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.console.ConsoleRunner
import dev.teo.mydb.plugin.console.ConsoleService

/** Settings → Database:. */
class SettingsTest : MyDbTestCase() {
    private val settings get() = MyDbSettings.get()
    private var saved = MyDbSettings.Values()

    override fun setUp() {
        super.setUp()
        saved = MyDbSettings.Values().also { com.intellij.util.xmlb.XmlSerializerUtil.copyBean(settings, it) }
    }

    override fun tearDown() {
        try {
            com.intellij.util.xmlb.XmlSerializerUtil.copyBean(saved, settings)
            ConsoleRunner.confirmHook = null
        } finally { super.tearDown() }
    }

    fun `test database settings pages are registered under Database`() {
        val eps = Configurable.APPLICATION_CONFIGURABLE.extensionList
        assertNotNull(eps.singleOrNull { it.id == "mydb.database" })
        val pages = eps.filter { it.parentId == "mydb.database" }.map { it.displayName ?: it.id }
        assertEquals(listOf("Query Execution", "Data Editor and Viewer", "CSV Formats", "Database Explorer", "Query Files and Consoles", "Other"), pages)
        val projectPages = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.parentId == "mydb.database" }.map { it.displayName ?: it.id }
        assertEquals(listOf("SQL Resolution Scopes"), projectPages)
        // every page can be created and shows a component
        for (ep in eps.filter { (it.id as String?)?.startsWith("mydb.database") == true }) {
            val c = (ep as ConfigurableEP<*>).createConfigurable()!!
            try { assertNotNull(ep.id, c.createComponent()) } finally { c.disposeUIResources() }
        }
    }

    fun `test caret inside policy smallest runs the statement without asking`() {
        // situation: two statements, caret right after the first semicolon
        sql("select 1;<caret>\nselect 2;")
        settings.caretInside = CaretInsidePolicy.ASK
        assertEquals(2, ConsoleRunner.targets(myFixture.editor).size)
        settings.caretInside = CaretInsidePolicy.SMALLEST
        val single = ConsoleRunner.targets(myFixture.editor).single()
        assertEquals("select 1", (single as RunTarget.Statement).statement.text.trimEnd(';'))
    }

    fun `test run chooser ends with a link to the execution settings`() {
        sql("select 1;<caret>\nselect 2;")
        settings.caretInside = CaretInsidePolicy.ASK
        val step = ConsoleRunner.chooserStep(project, ConsoleRunner.targets(myFixture.editor)) {}
        assertEquals(ConsoleRunner.CUSTOMIZE, step.values.last())
        assertNotNull(step.getSeparatorAbove(step.values.last()))
        assertNull(step.getSeparatorAbove(step.values.first()))
    }

    fun `test unsafe statements ask first unless the setting is off`() {
        assertTrue(dev.teo.mydb.core.exec.UnsafeStatements.isUnsafe("delete from shop.orders"))
        assertFalse(dev.teo.mydb.core.exec.UnsafeStatements.isUnsafe("delete from shop.orders where id in (select 1)"))
        assertTrue(dev.teo.mydb.core.exec.UnsafeStatements.isUnsafe("update t set a = (select b from u where u.id = 1)"))
        assertTrue(dev.teo.mydb.core.exec.UnsafeStatements.isUnsafe("with x as (select 1) delete from t"))
        assertFalse(dev.teo.mydb.core.exec.UnsafeStatements.isUnsafe("select * from t -- delete from t"))

        val config = freshDataSource("unsafe-ds")
        val f = myFixture.configureByText("unsafe.sql", "delete from shop.reviews;")
        dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project).bind(f.virtualFile, config.id, "shop")
        val ctx = dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project).context(f.virtualFile)!!
        val target = RunTarget.Statement(dev.teo.mydb.core.exec.StatementSplitter.split("delete from shop.reviews;").single())
        val asked = mutableListOf<List<String>>()
        ConsoleRunner.confirmHook = { asked += it; false }
        try {
            ConsoleRunner.execute(project, f.virtualFile, ctx, target)
            assertEquals(listOf(listOf("delete from shop.reviews")), asked)
            assertNull("declined: nothing ran", ConsoleService.getInstance(project).existing(f.virtualFile.url))
            settings.confirmUnsafeStatements = false
            ConsoleRunner.execute(project, f.virtualFile, ctx, target)
            assertEquals(1, asked.size)
        } finally {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            ConsoleService.getInstance(project).forget(f.virtualFile.url)
        }
    }

    fun `test execution time markers follow the setting`() {
        sql("select 1;")
        val st = dev.teo.mydb.core.exec.StatementSplitter.split("select 1;").single()
        val runs = listOf(ConsoleRunner.StatementRun(st, true, 12))
        settings.showExecutionTime = false
        dev.teo.mydb.plugin.console.RunMarkers.show(myFixture.editor, runs)
        assertEquals(emptyList<String>(), dev.teo.mydb.plugin.console.RunMarkers.shown(myFixture.editor).second)
        settings.showExecutionTime = true
        dev.teo.mydb.plugin.console.RunMarkers.show(myFixture.editor, runs)
        assertEquals(listOf("12 ms"), dev.teo.mydb.plugin.console.RunMarkers.shown(myFixture.editor).second)
    }

    fun `test resolution scope gives SQL files of a directory a data source`() {
        val f = myFixture.addFileToProject("reports/monthly.sql", "select * from shop.orders;").virtualFile
        val binding = dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project)
        assertNull(binding.context(f))
        val page = ResolutionScopesConfigurable(project)
        try {
            page.createComponent()
            page.addRow(f.parent.url, dataSource.id)
            assertTrue(page.isModified)
            page.apply()
        } finally { page.disposeUIResources() }
        assertEquals(dataSource.id, binding.context(f)?.config?.id)
        myFixture.configureFromExistingVirtualFile(f)
        val errors = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertEquals(errors.map { it.description }.toString(), 0, errors.size)
        binding.setScopes(emptyList())
        assertNull(binding.context(f))
    }

    fun `test page size setting applies to new sessions`() {
        settings.pageSize = 7
        val handle = ApplicationManager.getApplication().executeOnPooledThread<dev.teo.mydb.plugin.console.SessionHandle> {
            ConsoleService.getInstance(project).session("page-size", "page-size", dataSource)
        }.get()
        try { assertEquals(7, handle.session.pageSize) } finally { ConsoleService.getInstance(project).close("page-size") }
    }
}
