package dev.teo.mydb.core.plan

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.exec.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Execution plans. */
class PlanAcceptanceTest {
    private val query = "select o.id, c.name, sum(i.qty) from shop.orders o join shop.customers c on c.id = o.customer_id " +
        "join shop.order_items i on i.order_id = o.id where o.status = 'paid' group by o.id, c.name"

    private fun session(cfg: dev.teo.mydb.core.datasource.DataSourceConfig = SeededPostgres.config()) =
        Session(Connector().open(cfg, SeededPostgres.PASSWORD))

    @Test
    fun `explain sends a json explain and builds a tree`() {
        session().use { s ->
            val (plan, log) = SeededPostgres.captureStatements { QueryPlans.explain(s, query, analyze = false) }
            assertTrue(log.any { it.startsWith("EXPLAIN (FORMAT JSON) select o.id") }, log.toString())
            assertEquals("Select", plan.root.operation)
            assertNull(plan.root.flatten()[1].actualRows)
        }
    }

    @Test
    fun `nodes are renamed and carry table parameters`() {
        session().use { s ->
            val nodes = QueryPlans.explain(s, query, analyze = false).root.flatten()
            val scan = nodes.first { it.serverNodeType == "Seq Scan" && "customers" in it.parameters }
            assertEquals("Full Scan (Seq Scan)", scan.operation)
            assertEquals("Full Scan (Seq Scan) of customers", scan.title)
            assertTrue(scan.parameters.startsWith("table: customers;"))
            assertTrue(nodes.any { it.serverNodeType == "Hash" && it.operation == "Transformation (Hash)" })
        }
    }

    @Test
    fun `property panel lists all node properties`() {
        session().use { s ->
            val scan = QueryPlans.explain(s, query, analyze = false).root.flatten().first { it.serverNodeType == "Seq Scan" }
            assertTrue(listOf("Node Type", "Relation Name", "Alias", "Startup Cost", "Total Cost", "Plan Rows", "Plan Width").all { it in scan.properties })
        }
    }

    @Test
    fun `analyze adds actual measurements and timings`() {
        session().use { s ->
            val (plan, log) = SeededPostgres.captureStatements { QueryPlans.explain(s, query, analyze = true) }
            assertTrue(log.any { it.startsWith("EXPLAIN (FORMAT JSON, ANALYSE)") })
            assertNotNull(plan.root.properties["Execution Time"])
            assertNotNull(plan.root.properties["Planning Time"])
            assertNotNull(plan.root.children.single().actualRows)
        }
    }

    @Test
    fun `analyze of dml leaves data unchanged`() {
        val cfg = SeededPostgres.freshDatabase()
        session(cfg).use { s ->
            QueryPlans.explain(s, "update shop.products set price = 0", analyze = true)
            SeededPostgres.connect(cfg) { c ->
                c.createStatement().executeQuery("select count(*) from shop.products where price = 0").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
            assertTrue(s.connection.autoCommit)
        }
    }
}
