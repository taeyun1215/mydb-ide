package dev.teo.mydb.core.mysql

import dev.teo.mydb.core.connection.ConnectionTestResult
import dev.teo.mydb.core.connection.ConnectionTester
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.edit.GridEdits
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.core.edit.UserText
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.SqlDialect
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.introspect.Introspector
import dev.teo.mydb.core.plan.QueryPlans
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MySQL support against MySQL 8.4 itself (the MariaDB driver with `permitMysqlScheme`). */
class MysqlServerTest {
    companion object {
        // a plain container: the testcontainers MySQL module needs the GPL MySQL driver for its readiness check
        val server: GenericContainer<*> by lazy {
            GenericContainer("mysql:8.4").withExposedPorts(3306)
                .withEnv("MYSQL_ROOT_PASSWORD", "root").withEnv("MYSQL_DATABASE", "shop").withEnv("MYSQL_USER", "obs").withEnv("MYSQL_PASSWORD", "obs")
                .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1).withStartupTimeout(java.time.Duration.ofMinutes(3)))
                .also { c ->
                c.start()
                java.sql.DriverManager.getConnection("jdbc:mariadb://${c.host}:${c.firstMappedPort}/shop?allowPublicKeyRetrieval=true", "obs", "obs").use { conn ->
                    conn.createStatement().use { st ->
                        st.executeUpdate("create table customers (id int auto_increment primary key, name varchar(100) not null)")
                        st.executeUpdate("create table orders (id int auto_increment primary key, customer_id int not null, status varchar(16) default 'new', " +
                            "constraint orders_customer_fk foreign key (customer_id) references customers(id))")
                        st.executeUpdate("insert into customers(name) values ('a'), ('b')")
                        st.executeUpdate("insert into orders(customer_id) values (1), (2)")
                    }
                }
            }
        }
    }

    // MySQL 8 authenticates with caching_sha2_password; over plain TCP the driver may fetch the server key
    private fun config() = DataSourceConfig(name = "mysql8", dbms = Dbms.MYSQL,
        url = "jdbc:mysql://${server.host}:${server.firstMappedPort}/shop", user = "obs", driverProperties = mapOf("allowPublicKeyRetrieval" to "true"))

    @Test
    fun `MySQL 8 connection, introspection, grid edit and plan`() {
        val r = ConnectionTester().test(config(), "obs")
        assertTrue(r is ConnectionTestResult.Success && r.productName.contains("MySQL"), r.toString())
        val shop = Introspector.forDbms(Dbms.MYSQL).introspect(config(), "obs").databases.single().schema("shop")!!
        assertEquals(listOf("customers", "orders"), shop.tables.map { it.name })
        assertEquals("orders_customer_fk", shop.table("orders")!!.foreignKeys.single().name)
        Session(Connector().open(config(), "obs")).use { s ->
            val page = (s.execute("select * from orders order by id", 10) as StatementResult.Rows).page
            assertEquals("shop" to "orders", page.sourceTable)
            val edits = GridEdits("shop", "orders", page, listOf("id"), dialect = SqlDialect.of(s.connection))
            edits.setValue(0, 2, UserText("paid"))
            assertEquals(SubmitResult.Ok(1), edits.submit(s))
            val plan = QueryPlans.explain(s, "select * from orders where customer_id = 1", false)
            assertTrue(plan.root.children.isNotEmpty())
        }
    }
}
