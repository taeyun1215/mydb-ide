package dev.teo.mydb.plugin.diagram

import com.intellij.openapi.util.Disposer
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

/** ERD through the plugin layer: (the seed has 4 tables, 1 view, 3 foreign keys). */
class DiagramTest : MyDbTestCase() {
    private val shop get() = TestDatabase.model.schema("obs", "shop")!!

    override fun tearDown() {
        try { DiagramPositions.file(project, "x")?.parent?.let { dir -> if (Files.exists(dir)) dir.toFile().deleteRecursively() } } finally { super.tearDown() }
    }

    fun `test schema diagram has a card per relation and an edge per foreign key`() {
        val file = DiagramEditors.erdFile(project, dataSource.id, "obs", shop)
        assertEquals("shop", file.name)
        assertEquals(setOf("customers", "orders", "order_items", "products", "order_totals"), file.diagram.nodes.map { it.id }.toSet())
        assertEquals(setOf("orders->customers", "order_items->orders", "order_items->products"), file.diagram.edges.filter { it.kind == dev.teo.mydb.core.diagram.EdgeKind.FOREIGN_KEY }.map { "${it.from}->${it.to}" }.toSet())
        assertEquals(setOf("order_totals->orders", "order_totals->order_items", "order_totals->products"),
            file.diagram.edges.filter { it.kind == dev.teo.mydb.core.diagram.EdgeKind.VIEW }.map { "${it.from}->${it.to}" }.toSet())
    }

    fun `test view options change the cards and keep positions`() {
        val editor = DiagramEditor(project, DiagramEditors.erdFile(project, dataSource.id, "obs", shop))
        try {
            val d = editor.file.diagram
            val before = d.node("customers").let { it.x to it.y }
            val fullHeight = d.node("customers").height
            editor.setOptions(dev.teo.mydb.core.diagram.ErdOptions(columns = dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.KEYS, viewEdges = false))
            assertEquals(listOf("id"), d.node("customers").rows.map { it.second })
            assertTrue(d.node("customers").height < fullHeight)
            assertEquals(before, d.node("customers").let { it.x to it.y })
            assertTrue(d.visibleEdges.none { it.kind == dev.teo.mydb.core.diagram.EdgeKind.VIEW })
            assertTrue(editor.canvas.image().width > 100)
        } finally { Disposer.dispose(editor) }
    }

    fun `test table diagram shows the table with its parents and children`() {
        val file = DiagramEditors.erdFile(project, dataSource.id, "obs", shop, "orders")
        assertEquals("orders", file.name)
        assertEquals(setOf("orders", "customers", "order_items"), file.diagram.nodes.map { it.id }.toSet())
    }

    fun `test PNG export is a readable image`() {
        val editor = DiagramEditor(project, DiagramEditors.erdFile(project, dataSource.id, "obs", shop))
        try {
            val bytes = ByteArrayOutputStream().also { ImageIO.write(editor.canvas.image(), "png", it) }.toByteArray()
            assertTrue(bytes.size > 1000)
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertTrue(image.width > 100 && image.height > 100)
        } finally { Disposer.dispose(editor) }
    }

    fun `test moved node keeps its position when the diagram is opened again`() {
        val first = DiagramEditor(project, DiagramEditors.erdFile(project, dataSource.id, "obs", shop))
        try { first.canvas.moveNode("customers", 512.0, 48.0) } finally { Disposer.dispose(first) }

        val again = DiagramEditors.erdFile(project, dataSource.id, "obs", shop)
        val customers = again.diagram.node("customers")
        assertEquals(512.0, customers.x)
        assertEquals(48.0, customers.y)
        // other diagrams (a single table) keep their own positions
        val table = DiagramEditors.erdFile(project, dataSource.id, "obs", shop, "orders")
        assertFalse(table.diagram.node("customers").x == 512.0 && table.diagram.node("customers").y == 48.0)
    }
}
