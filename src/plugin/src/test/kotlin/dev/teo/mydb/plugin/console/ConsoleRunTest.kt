package dev.teo.mydb.plugin.console

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import dev.teo.mydb.core.exec.RunTarget
import dev.teo.mydb.core.exec.RunTargets
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.grid.JdbcGridHookUp
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Integration tests of console execution and the results grid: */
class ConsoleRunTest : MyDbTestCase() {
    private lateinit var panel: ResultsPanel

    override fun setUp() {
        super.setUp()
        panel = ResultsPanel(project)
        Disposer.register(testRootDisposable, panel)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(panel)
            ConsoleService.getInstance(project).close(console.url)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }

    /** The console file of this test, created on first use. */
    private val console by lazy { sql("", schema = "shop").virtualFile }

    private fun run(sql: String): ConsoleRunner.RunOutcome {
        val file = console
        val ctx = SqlDataSourceBinding.getInstance(project).context(file)!!
        // blocking JDBC work runs off the EDT, as in the real runner
        val outcome = ApplicationManager.getApplication().executeOnPooledThread<ConsoleRunner.RunOutcome?> {
            ConsoleRunner.runStatements(project, file, ctx, StatementSplitter.split(sql))
        }.get()!!
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return outcome
    }

    private fun DataGrid.rows() = getDataModel(DataAccessType.DATABASE_DATA).rowCount
    private fun DataGrid.cell(row: Int, col: Int): Any? {
        val m = getDataModel(DataAccessType.DATABASE_DATA)
        return m.getValueAt(ModelIndex.forRow(m, row), ModelIndex.forColumn(m, col))
    }

    fun `test caret inside one statement runs it without asking`() {
        val text = "select id, sku from shop.products order by id;\nselect count(*) from shop.orders;"
        val targets = RunTargets.resolve(text, caret = 10)
        assertEquals(1, targets.size)
        val statement = (targets.single() as RunTarget.Statement).statement
        assertEquals("select id, sku from shop.products order by id", statement.text.trimEnd(';'))

        val outcome = run(statement.text)
        assertNull(outcome.error)
        assertEquals(listOf("obs.shop.products"), outcome.results.map { it.title })
        assertEquals(20, outcome.results.single().page.rows.size)
        val log = OutputLog.getInstance(project).transcript(outcome.handle.title)
        assertTrue(log.toString(), log.any { it.startsWith("obs.shop> select id, sku from shop.products") })
        assertTrue(log.toString(), log.any { it.startsWith("Fetched 20 rows") })
    }

    fun `test running the script gives one tab per result set`() {
        val outcome = run("select id from shop.customers order by id limit 3;\nselect count(*) as n from shop.orders;")
        assertEquals(2, outcome.executed)
        assertEquals(listOf("obs.shop.customers", "Result 1"), outcome.results.map { it.title })

        panel.showResults(outcome.handle, outcome.results)
        val view = panel.view(outcome.handle)
        assertEquals(listOf("Output", "obs.shop.customers", "Result 1"), (0 until view.tabs.tabCount).map(view.tabs::getTitleAt))
        assertEquals(3, view.grids[0].second.rows())
        // a gutter mark and a run time per statement
        RunMarkers.show(myFixture.editor, outcome.runs)
        val (gutter, times) = RunMarkers.shown(myFixture.editor)
        assertEquals(2, gutter.size)
        assertTrue(gutter.toString(), gutter.all { it.startsWith("Executed in ") })
        assertEquals(2, times.size)
        assertTrue(times.toString(), times.all { it.endsWith(" ms") })
        assertEquals(200L, (view.grids[1].second.cell(0, 0) as Number).toLong())
    }

    fun `test running again replaces the previous result tabs`() {
        val first = run("select 1 as a;\nselect 2 as b;")
        panel.showResults(first.handle, first.results)
        val second = run("select 3 as c;")
        panel.showResults(second.handle, second.results)
        val view = panel.view(second.handle)
        assertEquals(listOf("Output", "Result 1"), (0 until view.tabs.tabCount).map(view.tabs::getTitleAt))
        assertEquals(3, (view.grids.single().second.cell(0, 0) as Number).toInt())
    }

    fun `test error stops the script and is logged with its SQLSTATE`() {
        val outcome = run("select 1;\nselect * from shop.missing_table;\nselect 2;")
        assertEquals(2, outcome.executed)
        assertEquals(1, outcome.results.size)
        assertTrue(outcome.error!!, outcome.error!!.startsWith("[42P01]"))
        assertTrue(OutputLog.getInstance(project).transcript(outcome.handle.title).last().startsWith("[42P01]"))
    }

    fun `test page size limits rows and next page continues`() {
        val handle = ConsoleService.getInstance(project).session(console.url, console.nameWithoutExtension, dataSource)
        handle.session.pageSize = 10
        val outcome = run("select id from shop.customers order by id;")
        assertEquals(10, outcome.results.single().page.rows.size)

        panel.showResults(outcome.handle, outcome.results)
        val grid = panel.view(outcome.handle).grids.single().second
        assertEquals(10, grid.rows())
        assertEquals(1, (grid.cell(0, 0) as Number).toInt())

        val hookUp = grid.dataHookup as JdbcGridHookUp
        hookUp.loader.loadNextPage(GridRequestSource(null))
        waitFor("next page") { grid.rows() == 10 && (grid.cell(0, 0) as Number).toInt() == 11 }
        assertEquals(11, hookUp.pageModel.pageStart)
        assertEquals(20, hookUp.pageModel.pageEnd)
    }

    fun `test history lists executed statements in order and pastes into the console`() {
        val history = ConsoleService.getInstance(project).history
        history.list(dataSource.id).forEach { history.remove(dataSource.id, it) }
        run("select 1 as first;\nselect 2 as second;")
        val dialog = HistoryDialog(project, dataSource, myFixture.editor)
        try {
            assertEquals(listOf("select 1 as first", "select 2 as second"), dialog.model.items.map { it.trimEnd(';') })
            dialog.select(0)
            dialog.paste()
            assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("select 1 as first"))
        } finally { dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) }
    }
}
