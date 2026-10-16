package dev.teo.mydb.core.diagram

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.core.history.QueryHistory
import dev.teo.mydb.core.introspect.PostgresIntrospector
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Diagram layout and query history. */
class DiagramAcceptanceTest {
    private val shop by lazy {
        val cfg = SeededPostgres.config().let { it.copy(introspectionScope = it.introspectionScope.plus(SchemaRef("obs", "shop"))) }
        PostgresIntrospector().introspect(cfg, SeededPostgres.PASSWORD).schema("obs", "shop")!!
    }

    @Test
    fun `nodes and foreign key edges`() {
        val d = Diagrams.erd(shop)
        assertEquals(setOf("customers", "order_items", "orders", "products", "order_totals"), d.nodes.map { it.id }.toSet())
        assertEquals(setOf("orders" to "customers", "order_items" to "orders", "order_items" to "products"),
            d.edges.filter { it.kind == EdgeKind.FOREIGN_KEY }.map { it.from to it.to }.toSet())
    }

    @Test
    fun `view lines go to the tables the view reads and options filter the cards`() {
        val d = Diagrams.erd(shop)
        assertEquals(setOf("orders", "order_items", "products"), d.edges.filter { it.kind == EdgeKind.VIEW }.map { it.to }.toSet())
        assertTrue(d.edges.filter { it.kind == EdgeKind.VIEW }.all { it.from == "order_totals" })
        d.apply(ErdOptions(viewEdges = false))
        assertTrue(d.visibleEdges.none { it.kind == EdgeKind.VIEW })
        assertTrue("order_totals" !in DiagramExport.mermaid(d).lines().filter { "||--o{" in it }.joinToString())

        d.apply(ErdOptions(columns = ErdOptions.ColumnMode.KEYS))
        assertEquals(listOf("id"), d.node("customers").rows.map { it.second })
        assertEquals(listOf("order_id", "product_id"), d.node("order_items").rows.map { it.second }.sorted())
        d.apply(ErdOptions(columns = ErdOptions.ColumnMode.NONE))
        assertEquals(0, d.node("customers").rows.size)
        d.apply(ErdOptions(types = false))
        assertTrue(d.node("customers").rows.all { it.third.isEmpty() })
        assertEquals(4, d.node("customers").rows.size)
    }

    @Test
    fun `edge labels`() {
        assertEquals("customer_id:id", Diagrams.erd(shop).edges.first { it.from == "orders" }.label)
    }

    @Test
    fun `key columns are listed last`() {
        assertEquals(listOf("name", "email", "created_at", "id"), Diagrams.erd(shop).node("customers").rows.map { it.second })
    }

    @Test
    fun `parents are laid out above children`() {
        val d = Diagrams.erd(shop)
        LayeredLayout.apply(d)
        d.edges.forEach { e -> assertTrue(d.node(e.from).y > d.node(e.to).y, "$e") }
    }

    @Test
    fun `mermaid export`() {
        val m = DiagramExport.mermaid(Diagrams.erd(shop))
        assertTrue(m.startsWith("erDiagram\n"))
        assertEquals(5, Regex("^    \\w+ \\{$", RegexOption.MULTILINE).findAll(m).count())
        assertEquals(3, m.lines().count { "||--o{" in it })
        assertTrue("        integer id PK" in m)
    }

    @Test
    fun `graphml and drawio exports are well-formed and complete`() {
        val d = Diagrams.erd(shop)
        val parse = { x: String -> javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(x.byteInputStream()) }
        val g = parse(DiagramExport.graphml(d))
        assertEquals(d.nodes.size, g.getElementsByTagName("node").length)
        assertEquals(d.edges.size, g.getElementsByTagName("edge").length)
        val x = parse(DiagramExport.drawio(d))
        val cells = x.getElementsByTagName("mxCell")
        assertEquals(2 + d.nodes.size + d.edges.size, cells.length)
    }

    @Test
    fun `other text exports`() {
        val d = Diagrams.erd(shop)
        LayeredLayout.apply(d)
        assertTrue(DiagramExport.plantUml(d).trim().endsWith("@enduml"))
        assertTrue("orders -> customers" in DiagramExport.dot(d))
        assertTrue(DiagramExport.svg(d).startsWith("<svg"))
    }

    @Test
    fun `plan diagram keeps every node with the root leftmost`() {
        dev.teo.mydb.core.exec.Session(dev.teo.mydb.core.connection.Connector().open(SeededPostgres.config(), SeededPostgres.PASSWORD)).use { s ->
            val plan = dev.teo.mydb.core.plan.QueryPlans.explain(s, "select * from shop.orders o join shop.customers c on c.id = o.customer_id", false)
            val d = Diagrams.plan(plan.root)
            assertEquals(plan.root.flatten().size, d.nodes.size)
            LayeredLayout.apply(d, LayeredLayout.Direction.LEFT_RIGHT)
            assertEquals(0.0, d.node("0").x)
            assertTrue(d.nodes.all { it.x >= 0.0 })
        }
    }

    @Test
    fun `console statements are recorded`() {
        val h = QueryHistory(Files.createTempDirectory("hist"))
        h.add("ds", "select * from shop.customers where id < 30;")
        h.add("ds", "select sku from shop.products")
        h.add("ds", "select * from shop.customers where id < 30") // re-run moves to the end
        assertEquals(listOf("select sku from shop.products", "select * from shop.customers where id < 30"), h.list("ds"))
    }
}
