package dev.teo.mydb.plugin.console

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.datasource.IsolationLevel
import dev.teo.mydb.core.datasource.TxMode
import dev.teo.mydb.core.exec.DdlChange
import dev.teo.mydb.core.exec.DdlStatements
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.core.exec.UnsafeStatements
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.inspections.SqlMissingWhereInspection

/** DDL run from a console or script refreshes the metadata it changed. */
class DdlRefreshTest : MyDbTestCase() {
    private lateinit var config: DataSourceConfig
    private val consoles get() = ConsoleService.getInstance(project)
    private val file get() = myFixture.file.virtualFile

    override fun setUp() {
        super.setUp()
        config = freshDataSource("ddl-ds")
    }

    override fun tearDown() {
        try { myFixture.file?.virtualFile?.let { consoles.forget(it.url) }; PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() } finally { super.tearDown() }
    }

    private fun console(text: String) {
        val f = myFixture.configureByText("ddl_console.sql", text)
        SqlDataSourceBinding.getInstance(project).bind(f.virtualFile, config.id, "shop")
    }

    private fun run(sql: String) = ApplicationManager.getApplication().executeOnPooledThread<ConsoleRunner.RunOutcome?> {
        ConsoleRunner.runStatements(project, file, SqlDataSourceBinding.getInstance(project).context(file)!!, StatementSplitter.split(sql))
    }.get()!!.also { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }

    private fun columns(table: String): List<String> {
        val ctx = SqlDataSourceBinding.getInstance(project).context(file)!!
        return ModelService.getInstance(project).rawModel(config.id)!!.schema(ctx.database, "shop")!!.table(table)!!.columns.map { it.name }
    }

    private fun unknownColumnErrors() = myFixture.doHighlighting()
        .filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("Unknown column") == true }.map { it.text }

    fun `test column added from the console is known right after the run`() {
        console("alter table shop.orders add column ddl_note text;\nselect o.ddl_note from shop.orders o;")
        assertEquals(listOf("ddl_note"), unknownColumnErrors())

        val outcome = run("alter table shop.orders add column ddl_note text")
        assertNull(outcome.error)
        assertTrue(columns("orders").contains("ddl_note"))
        assertEquals(emptyList<String>(), unknownColumnErrors())
    }

    fun `test unqualified DDL refreshes the console schema`() {
        console("")
        run("create table ddl_made (id int primary key, label text)")
        assertEquals(listOf("id", "label"), columns("ddl_made"))
        run("drop table ddl_made")
        val ctx = SqlDataSourceBinding.getInstance(project).context(file)!!
        assertNull(ModelService.getInstance(project).rawModel(config.id)!!.schema(ctx.database, "shop")!!.table("ddl_made"))
    }

    fun `test DDL in a manual transaction is refreshed on commit and dropped on rollback`() {
        console("")
        consoles.setTxSettings(file.url, config, TxSettings(TxMode.MANUAL, IsolationLevel.DATABASE_DEFAULT))

        run("alter table shop.orders add column ddl_rolled text")
        val handle = consoles.existing(file.url)!!
        assertEquals(listOf(RefreshTarget.Schema(SqlDataSourceBinding.getInstance(project).context(file)!!.database, "shop")), handle.pendingRefresh.toList())
        ApplicationManager.getApplication().executeOnPooledThread { handle.session.rollback() }.get()
        assertTrue(handle.pendingRefresh.isEmpty())
        assertFalse(columns("orders").contains("ddl_rolled"))

        run("alter table shop.orders add column ddl_committed text")
        assertFalse("not visible before commit", columns("orders").contains("ddl_committed"))
        ApplicationManager.getApplication().executeOnPooledThread { handle.session.commit() }.get()
        waitFor("refresh after commit") { columns("orders").contains("ddl_committed") }
        assertTrue(handle.pendingRefresh.isEmpty())
    }

    fun `test refresh can be switched off`() {
        console("")
        val settings = dev.teo.mydb.plugin.settings.MyDbSettings.get()
        settings.refreshAfterDdl = false
        try {
            run("alter table shop.orders add column ddl_off text")
            assertFalse(columns("orders").contains("ddl_off"))
        } finally { settings.refreshAfterDdl = true }
    }

    fun `test queries and DML do not refresh`() {
        console("")
        val before = ModelService.getInstance(project).rawModel(config.id)
        run("select 1; update shop.products set title = title where id = 1")
        assertSame(before, ModelService.getInstance(project).rawModel(config.id))
    }

    fun `test DDL classification`() {
        assertNull(DdlStatements.change("select * from shop.orders"))
        assertNull(DdlStatements.change("update shop.orders set id = 1"))
        assertNull(DdlStatements.change("create temporary table t (id int)"))
        assertNull(DdlStatements.change("-- alter table x\nselect 1"))
        assertEquals(DdlChange(setOf("maum"), false), DdlStatements.change("ALTER TABLE maum.staff ADD COLUMN password varchar(255)"))
        assertEquals(DdlChange(setOf("Maum"), false), DdlStatements.change("alter table \"Maum\".staff add c int"))
        assertEquals(DdlChange(setOf("maum"), false), DdlStatements.change("alter table `maum`.`staff` add c int"))
        assertEquals(DdlChange(emptySet(), false), DdlStatements.change("create table t (id int)"))
        assertEquals(DdlChange(setOf("a", "b"), false), DdlStatements.change("alter table a.t set schema b"))
        assertEquals(DdlChange(setOf("s", "t"), false), DdlStatements.change("comment on column s.t.c is 'x.y'"))
        assertEquals(DdlChange(emptySet(), true), DdlStatements.change("create schema if not exists reports"))
        assertEquals(DdlChange(emptySet(), true), DdlStatements.change("drop database old"))
        assertEquals(DdlChange(setOf("app"), false),
            DdlStatements.change("create or replace function app.f() returns int as \$\$ select other.g() \$\$ language sql"))
        assertEquals(DdlChange(setOf("a", "b"), false), DdlStatements.change("rename table a.t1 to b.t1"))
        assertNull(DdlStatements.change("comment 'x'"))
    }

    fun `test refresh targets`() {
        console("")
        val ctx = SqlDataSourceBinding.getInstance(project).context(file)!!
        val model = ModelService.getInstance(project).rawModel(config.id)
        val db = ctx.database
        assertEquals(emptyList<RefreshTarget>(), DdlRefresh.targets(Dbms.POSTGRES, model, db, "shop", listOf("select 1")))
        // qualifiers that are not schemas (aliases, tables) are ignored; the session schema is always included
        assertEquals(listOf(RefreshTarget.Schema(db, "shop")),
            DdlRefresh.targets(Dbms.POSTGRES, model, db, "shop", listOf("create view v as select o.id from shop.orders o")))
        assertEquals(listOf(RefreshTarget.All), DdlRefresh.targets(Dbms.POSTGRES, model, db, "shop", listOf("create schema extra")))
        assertEquals(listOf(RefreshTarget.All), DdlRefresh.targets(Dbms.MYSQL, model, db, "shop", listOf("alter table shop.orders add c int")))
    }

    fun `test update joined on a condition is not reported as changing every row`() {
        assertFalse(UnsafeStatements.isUnsafe("UPDATE maum.staff s JOIN maum.user u ON u.uuid = s.user_id SET s.password = u.password"))
        assertFalse(UnsafeStatements.isUnsafe("update a inner join b using (id) set a.x = b.x"))
        assertTrue(UnsafeStatements.isUnsafe("update a left join b on b.id = a.id set a.x = b.x"))
        assertTrue(UnsafeStatements.isUnsafe("update a, b set a.x = b.x"))
        // PostgreSQL: joins after SET … FROM do not limit the target
        assertTrue(UnsafeStatements.isUnsafe("update a set x = b.x from b join c on c.id = b.id"))

        myFixture.enableInspections(SqlMissingWhereInspection())
        sql("update shop.orders o join shop.customers c on c.id = o.customer_id set o.status = c.name;\n" +
            "update shop.orders o left join shop.customers c on c.id = o.customer_id set o.status = c.name;\n" +
            "update shop.orders set status = c.name from shop.customers c join shop.products p on p.id = c.id")
        val warnings = myFixture.doHighlighting().filter { it.description?.contains("without WHERE") == true }
        assertEquals(2, warnings.size)
    }
}
