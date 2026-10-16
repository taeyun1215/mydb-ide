package dev.teo.mydb.core.mysql

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
import dev.teo.mydb.core.exec.SqlDialect
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.exec.TableQuery
import dev.teo.mydb.core.introspect.Introspector
import dev.teo.mydb.core.model.IdentityKind
import dev.teo.mydb.core.plan.QueryPlans
import org.testcontainers.mariadb.MariaDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MySQL support against a MariaDB server reached with a `jdbc:mysql:` URL. */
class MysqlAcceptanceTest {
    companion object {
        val server: MariaDBContainer by lazy {
            MariaDBContainer("mariadb:11.4").withDatabaseName("shop").withUsername("obs").withPassword("obs").also { c ->
                c.start()
                java.sql.DriverManager.getConnection(c.jdbcUrl, "obs", "obs").use { conn ->
                    conn.createStatement().use { st ->
                        st.executeUpdate("create table customers (id int auto_increment primary key, name varchar(100) not null, email varchar(200) unique) comment 'people'")
                        st.executeUpdate("create table orders (id int auto_increment primary key, customer_id int not null, status varchar(16) default 'new', " +
                            "constraint orders_customer_fk foreign key (customer_id) references customers(id) on delete cascade)")
                        st.executeUpdate("create index orders_status_idx on orders(status)")
                        st.executeUpdate("create view open_orders as select o.id, c.name from orders o join customers c on c.id = o.customer_id where o.status = 'new'")
                        st.executeUpdate("insert into customers(name, email) values ('a', 'a@x'), ('b', 'b@x')")
                        st.executeUpdate("insert into orders(customer_id, status) values (1, 'new'), (2, 'paid'), (1, 'new')")
                    }
                }
            }
        }
    }

    private fun config() = DataSourceConfig(name = "mysql", dbms = Dbms.MYSQL,
        url = "jdbc:mysql://${server.host}:${server.firstMappedPort}/shop", user = "obs")

    @Test
    fun `fields give the MySQL URL and name`() {
        val form = ConnectionForm(Dbms.MYSQL)
        form.host = "db.local"; form.database = "shop"
        assertEquals("jdbc:mysql://db.local:3306/shop", form.url)
        assertEquals("root@db.local", form.name)
        assertEquals("`order`", SqlDialect.MYSQL.quote("order"))
        assertEquals("orders", SqlDialect.MYSQL.quote("orders"))
    }

    @Test
    fun `connection test`() {
        val r = ConnectionTester().test(config(), "obs")
        assertTrue(r is ConnectionTestResult.Success && r.productName.isNotEmpty(), r.toString())
    }

    @Test
    fun `introspection of the default schema`() {
        val m = Introspector.forDbms(Dbms.MYSQL).introspect(config(), "obs")
        val shop = m.databases.single().schemas.single()
        assertEquals("shop", shop.name)
        assertEquals(listOf("customers", "open_orders", "orders"), shop.tables.map { it.name })
        val orders = shop.table("orders")!!
        assertEquals(listOf("id"), orders.primaryKey?.columns)
        assertEquals(IdentityKind.BY_DEFAULT, orders.column("id")!!.identity)
        assertEquals("customers", orders.foreignKeys.single().refTable)
        assertEquals(dev.teo.mydb.core.model.FkAction.CASCADE, orders.foreignKeys.single().onDelete)
        assertTrue(orders.indexes.any { it.name == "orders_status_idx" && !it.backsConstraint })
        assertEquals(listOf("email"), shop.table("customers")!!.uniqueKeys.single().columns)
        assertEquals("people", shop.table("customers")!!.comment)
        assertTrue(shop.table("open_orders")!!.isView)
        assertTrue(orders.sourceDdl!!.startsWith("CREATE TABLE `orders`"), orders.sourceDdl)
        assertTrue("shop" in m.databases.single().allSchemaNames)
    }

    @Test
    fun `select is editable and a grid edit is submitted`() {
        Session(Connector().open(config(), "obs")).use { s ->
            val r = s.execute(TableQuery.select("shop", "orders", "", "id", 10, 0, SqlDialect.MYSQL), 10) as StatementResult.Rows
            assertEquals(3, r.page.rows.size)
            val plain = (s.execute("select * from orders order by id", 10) as StatementResult.Rows).page
            assertEquals("shop" to "orders", plain.sourceTable)
            val aliased = (s.execute("select o.id as oid, o.status st from orders o", 10) as StatementResult.Rows).page
            assertEquals(listOf("id", "status"), aliased.columns.map { it.baseColumn }, "aliases keep the original names")
            assertEquals("shop" to "orders", aliased.sourceTable)
            val edits = GridEdits("shop", "orders", plain, listOf("id"), dialect = SqlDialect.of(s.connection))
            edits.setValue(1, 2, UserText("cancelled"))
            assertEquals(SubmitResult.Ok(1), edits.submit(s))
            val after = s.execute("select status from orders where id = 2", 1) as StatementResult.Rows
            assertEquals("cancelled", after.page.rows.single()[0])
        }
    }

    @Test
    fun `query plan`() {
        Session(Connector().open(config(), "obs")).use { s ->
            val plan = QueryPlans.explain(s, "select o.id, c.name from orders o join customers c on c.id = o.customer_id where o.status = 'new'", false)
            assertEquals("Select", plan.root.operation)
            assertTrue(plan.root.children.any { it.parameters.contains("table: o;") || it.parameters.contains("table: orders;") }, plan.root.children.toString())
        }
    }
}
