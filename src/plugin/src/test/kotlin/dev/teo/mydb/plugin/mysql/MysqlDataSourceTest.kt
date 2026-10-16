package dev.teo.mydb.plugin.mysql

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.console.ConsoleRunner
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.explorer.DatabaseExplorerPanel
import dev.teo.mydb.plugin.explorer.ExplorerActions
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import org.testcontainers.mariadb.MariaDBContainer
import javax.swing.tree.DefaultMutableTreeNode

/** through the plugin (MariaDB server, `jdbc:mysql:` URL). */
class MysqlDataSourceTest : MyDbTestCase() {
    companion object {
        val server: MariaDBContainer by lazy {
            MariaDBContainer("mariadb:11.4").withDatabaseName("shop").withUsername("obs").withPassword("obs").also { c ->
                c.start()
                java.sql.DriverManager.getConnection(c.jdbcUrl, "obs", "obs").use { conn ->
                    conn.createStatement().use { st ->
                        st.executeUpdate("create table customers (id int auto_increment primary key, name varchar(100) not null)")
                        st.executeUpdate("create table orders (id int auto_increment primary key, customer_id int not null references customers(id), status varchar(16) default 'new')")
                        st.executeUpdate("insert into customers(name) values ('a'), ('b')")
                        st.executeUpdate("insert into orders(customer_id) values (1), (2)")
                    }
                }
            }
        }
    }

    private lateinit var mysql: DataSourceConfig

    override fun setUp() {
        super.setUp()
        mysql = DataSourceConfig(id = "mysql-ds", name = "obs@mariadb", dbms = Dbms.MYSQL, url = "jdbc:mysql://${server.host}:${server.firstMappedPort}/shop", user = "obs")
        DataSourceStorage.getInstance(project).save(mysql)
        DataSourceStorage.getInstance(project).setPassword(mysql, "obs")
        ModelService.getInstance(project).refreshNow(mysql)
    }

    override fun tearDown() {
        try {
            ConsoleService.getInstance(project).all().filter { it.dataSource.id == mysql.id }.forEach { ConsoleService.getInstance(project).close(it.key) }
            DataSourceStorage.getInstance(project).remove(mysql.id)
            ModelService.getInstance(project).remove(mysql.id)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }

    fun `test explorer shows MySQL schemas under the data source`() {
        val panel = DatabaseExplorerPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.refreshTree()
        val ds = TreeUtil.treeNodeTraverser(panel.tree.model.root as DefaultMutableTreeNode).toList()
            .map { (it as DefaultMutableTreeNode) }.first { (it.userObject as? ExplorerNode.DataSource)?.config?.id == mysql.id }
        val children = (0 until ds.childCount).map { (ds.getChildAt(it) as DefaultMutableTreeNode).userObject }
        assertEquals(listOf("shop"), children.map { (it as ExplorerNode.Schema).schema.name })
        val badge = ds.userObject as ExplorerNode.DataSource
        assertEquals(1, badge.shown)
    }

    fun `test backtick identifiers resolve against the MySQL model`() {
        val f = myFixture.configureByText("mysql_ticks.sql", "select `o`.`status`, c.name from `orders` `o` join customers c on c.id = o.customer_id;")
        SqlDataSourceBinding.getInstance(project).bind(f.virtualFile, mysql.id, "shop")
        val errors = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertEquals(errors.map { it.description }.toString(), 0, errors.size)
    }

    fun `test console select is editable and DDL comes from the server`() {
        val f = myFixture.configureByText("mysql_console.sql", "select * from orders;").virtualFile
        SqlDataSourceBinding.getInstance(project).bind(f, mysql.id, "shop")
        val ctx = SqlDataSourceBinding.getInstance(project).context(f)!!
        assertEquals("shop", ctx.schema)
        val outcome = ApplicationManager.getApplication().executeOnPooledThread<ConsoleRunner.RunOutcome?> {
            ConsoleRunner.runStatements(project, f, ctx, StatementSplitter.split("select * from orders;"))
        }.get()!!
        assertNull(outcome.error)
        assertEquals(2, outcome.results.single().page.rows.size)
        assertEquals(listOf("id"), outcome.results.single().edit?.keyColumns)
        val shop = ModelService.getInstance(project).model(mysql.id)!!.databases.single().schema("shop")!!
        val orders = ExplorerNode.Table(mysql, "def", "shop", shop.table("orders")!!)
        assertTrue(ExplorerActions.ddlText(project, orders).startsWith("CREATE TABLE `orders`"))
    }
}
