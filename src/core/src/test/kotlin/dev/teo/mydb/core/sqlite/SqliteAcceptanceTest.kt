package dev.teo.mydb.core.sqlite

import dev.teo.mydb.core.connection.ConnectionTestResult
import dev.teo.mydb.core.connection.ConnectionTester
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.datasource.ConnectionForm
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.edit.GridEdits
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.core.edit.UserText
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.exec.TableQuery
import dev.teo.mydb.core.introspect.Introspector
import dev.teo.mydb.core.plan.QueryPlans
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SQLite support on a temporary database file. */
class SqliteAcceptanceTest {
    private fun shopFile(): Path {
        val f = Files.createTempFile("shop", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:$f").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("create table customers (id integer primary key, name text not null, email text unique)")
                st.executeUpdate("create table orders (id integer primary key, customer_id integer not null references customers(id) on delete cascade, status text default 'new')")
                st.executeUpdate("create index orders_status_idx on orders(status)")
                st.executeUpdate("create view open_orders as select o.id, c.name from orders o join customers c on c.id = o.customer_id where o.status = 'new'")
                st.executeUpdate("insert into customers(name, email) values ('a', 'a@x'), ('b', 'b@x'), ('c', null)")
                st.executeUpdate("insert into orders(customer_id, status) values (1, 'new'), (2, 'paid'), (1, 'new')")
            }
        }
        return f
    }

    private fun config(f: Path, readOnly: Boolean = false) = DataSourceConfig(name = f.fileName.toString(), dbms = Dbms.SQLITE, url = "jdbc:sqlite:$f", readOnly = readOnly)

    @Test
    fun `file path gives the URL and the name`() {
        val form = ConnectionForm(Dbms.SQLITE)
        form.database = "/data/app/shop.sqlite"
        assertEquals("jdbc:sqlite:/data/app/shop.sqlite", form.url)
        assertEquals("shop.sqlite", form.name)
        form.editUrl("jdbc:sqlite:/tmp/other.db")
        assertEquals("/tmp/other.db", form.database)
        assertEquals("other.db", form.name)
    }

    @Test
    fun `connection test reports the SQLite version`() {
        val r = ConnectionTester().test(config(shopFile()), null)
        assertTrue(r is ConnectionTestResult.Success && r.productName == "SQLite" && r.productVersion.startsWith("3."), r.toString())
    }

    @Test
    fun `introspection reads main with keys, indexes, views and the stored DDL`() {
        val f = shopFile()
        val m = Introspector.forDbms(Dbms.SQLITE).introspect(config(f), null)
        val main = m.databases.single().schemas.single()
        assertEquals("main", main.name)
        assertEquals(listOf("customers", "open_orders", "orders"), main.tables.map { it.name })
        val orders = main.table("orders")!!
        assertEquals(listOf("id"), orders.primaryKey?.columns)
        assertEquals("customers", orders.foreignKeys.single().refTable)
        assertEquals(listOf("customer_id"), orders.foreignKeys.single().columns)
        assertEquals(listOf("id"), orders.foreignKeys.single().refColumns)
        assertEquals("'new'", orders.column("status")!!.default)
        assertTrue(orders.indexes.any { it.name == "orders_status_idx" && !it.backsConstraint })
        assertEquals(listOf("email"), main.table("customers")!!.uniqueKeys.single().columns)
        assertTrue(main.table("open_orders")!!.isView)
        assertTrue(orders.sourceDdl!!.startsWith("CREATE TABLE orders"), orders.sourceDdl)
        assertTrue(orders.sourceDdl!!.contains("CREATE INDEX orders_status_idx on orders(status);"), orders.sourceDdl)
    }

    @Test
    fun `console select with paging`() {
        Session(Connector().open(config(shopFile()), null)).use { s ->
            val r = s.execute(TableQuery.select("main", "orders", "", "", 2, 0), 2) as StatementResult.Rows
            assertEquals(2, r.page.rows.size)
            assertTrue(r.page.hasMore)
            // a plain table query is editable: its columns come from one base table
            val one = s.execute("select id, status from orders", 10) as StatementResult.Rows
            assertEquals("main" to "orders", one.page.sourceTable)
            assertEquals(listOf("id", "status"), one.page.columns.map { it.baseColumn })
            // an alias hides the origin column: read-only rather than a wrong UPDATE
            assertEquals(null, (s.execute("select id, status as st from orders", 10) as StatementResult.Rows).page.sourceTable)
            val join = s.execute("select o.id, c.name from orders o join customers c on c.id = o.customer_id", 10) as StatementResult.Rows
            assertEquals(null, join.page.sourceTable)
        }
    }

    @Test
    fun `grid edit submits an UPDATE by key`() {
        val f = shopFile()
        Session(Connector().open(config(f), null)).use { s ->
            val page = (s.execute("select * from orders order by id", 100) as StatementResult.Rows).page
            val edits = GridEdits("main", "orders", page, listOf("id"))
            edits.setValue(1, 2, UserText("cancelled"))
            edits.setValue(0, 1, UserText("2"))
            assertEquals(SubmitResult.Ok(2), edits.submit(s))
        }
        DriverManager.getConnection("jdbc:sqlite:$f").use { c ->
            c.createStatement().executeQuery("select customer_id, status from orders order by id").use { rs ->
                rs.next(); assertEquals(2, rs.getInt(1))
                rs.next(); assertEquals("cancelled", rs.getString(2))
            }
        }
    }

    @Test
    fun `query plan tree`() {
        Session(Connector().open(config(shopFile()), null)).use { s ->
            val plan = QueryPlans.explain(s, "select o.id, c.name from orders o join customers c on c.id = o.customer_id where o.status = 'new'", false)
            assertEquals("Select", plan.root.operation)
            val ops = plan.root.flatten().drop(1).map { it.operation }
            assertTrue(ops.any { it.startsWith("SCAN") || it.startsWith("SEARCH") }, ops.toString())
            assertNotNull(plan.root.flatten().firstOrNull { it.parameters.contains("table:") })
        }
    }

    @Test
    fun `read-only data source refuses changes`() {
        val f = shopFile()
        Session(Connector().open(config(f, readOnly = true), null)).use { s ->
            val r = s.execute("update orders set status = 'x'")
            assertTrue(r is StatementResult.Error, r.toString())
        }
        DriverManager.getConnection("jdbc:sqlite:$f").use { c ->
            c.createStatement().executeQuery("select count(*) from orders where status = 'x'").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
        }
    }
}
