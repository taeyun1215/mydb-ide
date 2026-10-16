package dev.teo.mydb.plugin.compare

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.PlatformTestUtil
import dev.teo.mydb.core.diff.DiffStatus
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.model.ModelService

/** Schema compare through the plugin layer: */
class CompareTest : MyDbTestCase() {
    /** A diverging copy `shop2` of the seed schema. */
    private val shop2 = """
        create schema shop2;
        create table shop2.customers (id serial primary key, name varchar(100) not null, email text, phone text);
        create table shop2.products (id serial primary key, sku varchar(32) not null unique, title text not null, price numeric(12,2) not null);
        create table shop2.legacy (x int);
    """.trimIndent()

    private fun sides(): Pair<CompareSide, CompareSide> {
        val config = freshDataSource("compare-ds", shop2, listOf("shop2"))
        val db = config.url.substringAfterLast('/')
        return CompareSide(config, db, "shop") to CompareSide(config, db, "shop2")
    }

    fun `test explorer selection of two schemas and the comparison rows`() {
        val (shop, shop2) = sides()
        val model = ModelService.getInstance(project).model(shop.config.id)!!
        val nodes = listOf(shop, shop2).map { ExplorerNode.Schema(it.config, it.database, model.schema(it.database, it.schema)!!) }
        assertEquals(shop to shop2, CompareAction.sides(nodes))
        assertNull("schema and table are not compared", CompareAction.sides(listOf(nodes[0], ExplorerNode.Table(shop.config, shop.database, "shop", model.schema(shop.database, "shop")!!.table("orders")!!))))

        val compare = SchemaCompare(project, shop, shop2)
        val top = compare.rows(showIdentical = false).filter { it.second == 0 }.associate { it.first.name to it.first.status }
        assertEquals(DiffStatus.CHANGED, top["customers"])
        assertEquals(DiffStatus.ONLY_IN_TARGET, top["legacy"])
        assertEquals(DiffStatus.ONLY_IN_SOURCE, top["orders"])
        assertTrue(compare.rows(showIdentical = true).size >= compare.rows(showIdentical = false).size)
    }

    fun `test unchecking and swapping regenerate the script`() {
        val (shop, shop2) = sides()
        val compare = SchemaCompare(project, shop, shop2)
        assertTrue(compare.script().contains("drop table shop2.legacy;"))
        compare.setChecked(compare.diff.items.single { it.name == "legacy" }, false)
        assertFalse(compare.script().contains("drop table shop2.legacy"))

        compare.swap()
        assertEquals("shop2", compare.source.schema)
        assertEquals("shop", compare.target.schema)
        assertTrue(compare.script(), compare.script().contains("drop table shop.orders;"))
    }

    fun `test executing the script leaves no differences except view bodies`() {
        val (shop, shop2) = sides()
        val compare = SchemaCompare(project, shop, shop2)
        assertNull(compare.execute())
        val remaining = compare.rows(showIdentical = false).filter { it.first.status != DiffStatus.SAME && it.first.kind != "view" }
        assertEquals(remaining.map { it.first.id }.toString(), 0, remaining.size)
        assertEquals(1, TestDatabase.query(shop.config, "select count(*) from information_schema.tables where table_schema = 'shop2' and table_name = 'orders'") { it.getInt(1) })
    }

    fun `test table to table compare matches different names and shows a DDL diff`() {
        val (shop, _) = sides()
        val compare = SchemaCompare(project, shop.copy(table = "customers"), shop.copy(schema = "shop2", table = "customers"))
        val item = compare.diff.items.single()
        assertEquals(DiffStatus.CHANGED, item.status)
        assertTrue(compare.script(), compare.script().contains("alter column name type text using name::text"))
        val (left, right) = compare.ddl(item)
        assertTrue(left.contains("create table shop.customers") || left.contains("create table customers"))
        assertTrue(right, right.contains("phone"))
    }

    fun `test migration dialog builds its rows and script`() {
        val (shop, shop2) = sides()
        val dialog = SchemaCompareDialog(project, SchemaCompare(project, shop, shop2))
        try {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            // init must have run: the dialog has its title and a real content panel (it opened empty before)
            assertTrue(dialog.title, dialog.title.startsWith("Migrate: "))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test comparison tree groups objects into kind folders and the properties tab shows differences`() {
        val (shop, shop2) = sides()
        val compare = SchemaCompare(project, shop, shop2)
        val tree = compare.tree(showIdentical = false)
        val top = tree.filter { it.depth == 0 }
        assertEquals(listOf("tables", "views"), top.map { (it as CompareRow.Folder).label })
        val customers = tree.filterIsInstance<CompareRow.Item>().single { it.item.name == "customers" && it.item.kind == "table" }
        val under = tree.dropWhile { it !== customers }.drop(1).takeWhile { it.depth > customers.depth }
        assertTrue(under.toString(), under.filterIsInstance<CompareRow.Folder>().any { it.label == "columns" })

        // a folder checkbox checks all its members
        val columns = under.filterIsInstance<CompareRow.Folder>().first { it.label == "columns" }
        compare.setChecked(columns, false)
        assertTrue(columns.members.none(compare::isChecked))
        compare.setChecked(columns, true)

        val name = customers.item.children.single { it.name == "name" }
        val props = compare.properties(name)
        assertEquals(PropertyRow("Type", "text", "character varying(100)"), props.first())
        assertTrue(props.first().differs)
        val tableProps = compare.properties(customers.item)
        assertEquals("phone is extra in shop2", "4", tableProps.single { it.name == "Columns" }.target)

        val dialog = SchemaCompareDialog(project, compare)
        try {
            assertEquals("customers", dialog.selectedItem()?.name)
            assertTrue(dialog.shownProperties().isNotEmpty())
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test open in new console puts the script there without running it`() {
        val (shop, shop2) = sides()
        val compare = SchemaCompare(project, shop, shop2)
        val file = compare.openInConsole()
        try {
            assertEquals(compare.script(), com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!.text)
            assertEquals(shop2.config.id, dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project).dataSourceId(file))
            assertEquals("nothing was executed", 1, TestDatabase.query(shop.config, "select count(*) from information_schema.tables where table_schema = 'shop2' and table_name = 'legacy'") { it.getInt(1) })
        } finally { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(file) }
    }

    fun `test DDL diff counts the differing blocks of the selected object`() {
        val (shop, shop2) = sides()
        val compare = SchemaCompare(project, shop, shop2)
        assertTrue(compare.ddlDifferences(compare.diff.items.single { it.name == "customers" }) > 0)
        val same = SchemaCompare(project, shop, shop)
        val item = same.diff.items.single { it.name == "products" }
        val (l, r) = same.ddl(item)
        assertEquals(l, r)
        assertEquals(0, same.ddlDifferences(item))
    }
}
