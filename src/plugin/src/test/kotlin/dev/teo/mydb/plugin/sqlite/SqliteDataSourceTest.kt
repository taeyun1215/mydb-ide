package dev.teo.mydb.plugin.sqlite

import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.console.ConsoleRunner
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.datasource.DataSourceDialog
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.explorer.ExplorerActions
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.grid.JdbcGridHookUp
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import java.nio.file.Files
import java.sql.DriverManager

/** through the plugin: data source, explorer model, console, table editor, DDL. */
class SqliteDataSourceTest : MyDbTestCase() {
    private lateinit var sqlite: DataSourceConfig
    private lateinit var file: java.nio.file.Path

    override fun setUp() {
        super.setUp()
        file = Files.createTempFile("plugin-shop", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("create table customers (id integer primary key, name text not null)")
                st.executeUpdate("create table orders (id integer primary key, customer_id integer references customers(id), status text default 'new')")
                st.executeUpdate("insert into customers(name) values ('a'), ('b')")
                st.executeUpdate("insert into orders(customer_id) values (1), (2), (1)")
            }
        }
        sqlite = DataSourceConfig(id = "sqlite-ds", name = file.fileName.toString(), dbms = Dbms.SQLITE, url = "jdbc:sqlite:$file")
        DataSourceStorage.getInstance(project).save(sqlite)
        ModelService.getInstance(project).refreshNow(sqlite)
    }

    override fun tearDown() {
        try {
            ConsoleService.getInstance(project).all().filter { it.dataSource.id == sqlite.id }.forEach { ConsoleService.getInstance(project).close(it.key) }
            DataSourceStorage.getInstance(project).remove(sqlite.id)
            ModelService.getInstance(project).remove(sqlite.id)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Files.deleteIfExists(file)
        } finally { super.tearDown() }
    }

    private fun main() = ModelService.getInstance(project).model(sqlite.id)!!.databases.single().schemas.single()

    fun `test model, DDL and PostgreSQL-only actions`() {
        assertEquals(listOf("customers", "orders"), main().tables.map { it.name })
        val db = ModelService.getInstance(project).model(sqlite.id)!!.databases.single().name
        val orders = ExplorerNode.Table(sqlite, db, "main", main().table("orders")!!)
        assertTrue(ExplorerActions.ddlText(project, orders).startsWith("CREATE TABLE orders"))
        for (id in listOf("MyDb.PgDump")) {
            val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id)
            val e = TestActionEvent.createTestEvent(action, com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project).add(ExplorerDataKeys.SELECTED, listOf(orders)).build())
            action.update(e)
            assertFalse("$id hidden for SQLite", e.presentation.isEnabledAndVisible)
        }
        assertEquals("delete from main.orders;", dev.teo.mydb.plugin.explorer.ObjectStatements.truncate("main", orders.table, Dbms.SQLITE))
        assertEquals(main().table("orders")!!.sourceDdl, dev.teo.mydb.plugin.generator.SqlGenerator.generate(project, orders, dev.teo.mydb.core.ddl.DdlOptions()))
    }

    fun `test console run gives an editable result and the table editor submits`() {
        val f = myFixture.configureByText("sqlite_console.sql", "select * from orders;").virtualFile
        SqlDataSourceBinding.getInstance(project).bind(f, sqlite.id, "main")
        val ctx = SqlDataSourceBinding.getInstance(project).context(f)!!
        val outcome = ApplicationManager.getApplication().executeOnPooledThread<ConsoleRunner.RunOutcome?> {
            ConsoleRunner.runStatements(project, f, ctx, StatementSplitter.split("select * from orders;"))
        }.get()!!
        assertNull(outcome.error)
        val tab = outcome.results.single()
        assertEquals(3, tab.page.rows.size)
        assertEquals(listOf("id"), tab.edit?.keyColumns)

        val db = ModelService.getInstance(project).model(sqlite.id)!!.databases.single().name
        val tf = dev.teo.mydb.plugin.table.TableDataFile(sqlite.id, db, "main", "orders")
        val editor = dev.teo.mydb.plugin.table.TableDataEditor(project, tf)
        try {
            waitFor("table editor") { editor.grid != null }
            val grid = editor.grid!!
            val hookUp = grid.dataHookup as JdbcGridHookUp
            val m = grid.getDataModel(DataAccessType.DATABASE_DATA)
            (hookUp.mutator as JdbcGridHookUp.Mutator).mutate(GridRequestSource(null), ModelIndexSet.forRows(m, 1), ModelIndexSet.forColumns(m, 2), "paid", false)
            (hookUp.mutator as JdbcGridHookUp.Mutator).submit(GridRequestSource(null), true)
            waitFor("submit") { hookUp.lastSubmit != null }
            assertEquals(SubmitResult.Ok(1), hookUp.lastSubmit)
        } finally { Disposer.dispose(editor); ConsoleService.getInstance(project).close(tf.sessionKey) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().executeQuery("select status from orders where id = 2").use { rs -> rs.next(); assertEquals("paid", rs.getString(1)) }
        }
    }

    fun `test create and modify table dialogs work on SQLite`() {
        val db = ModelService.getInstance(project).model(sqlite.id)!!.databases.single().name
        val draft = dev.teo.mydb.plugin.objects.TableDraft(main(), null, Dbms.SQLITE).apply {
            name = "notes"
            addColumn().apply { name = "id"; identity = true }
            addColumn().apply { name = "body"; type = "text"; notNull = true }
            addPrimaryKey("id")
            addIndex("body")
            refreshAutoNames()
        }
        assertTrue(draft.typeChoices.contains("blob"))
        assertTrue(draft.preview(), draft.preview().startsWith("create table notes\n(\n    id integer primary key autoincrement"))
        assertEquals(emptyList<String>(), draft.problems())
        assertNull(dev.teo.mydb.plugin.objects.ObjectDdl.execute(project, sqlite, db, draft, draft.statements()))
        val notes = main().table("notes")!!
        assertEquals(listOf("id"), notes.primaryKey?.columns)
        val edit = dev.teo.mydb.plugin.objects.TableDraft(main(), notes, Dbms.SQLITE)
        edit.addColumn().apply { name = "stars"; type = "integer"; default = "5" }
        assertEquals(listOf("alter table notes add stars integer default 5;"), edit.statements())
        assertNull(dev.teo.mydb.plugin.objects.ObjectDdl.execute(project, sqlite, db, edit, edit.statements()))
        assertNotNull(main().table("notes")!!.column("stars"))
        assertEquals(listOf("Table", "View"), dev.teo.mydb.plugin.objects.newKinds(Dbms.SQLITE))
    }

    fun `test data source dialog has a file form for SQLite`() {
        val dialog = DataSourceDialog(project, null, Dbms.SQLITE)
        try {
            val draft = dialog.drafts().last()
            assertEquals(Dbms.SQLITE, draft.config.dbms)
            assertTrue(draft.config.url, draft.config.url.startsWith("jdbc:sqlite:"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }
}
