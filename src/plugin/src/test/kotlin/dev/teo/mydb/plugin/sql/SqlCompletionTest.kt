package dev.teo.mydb.plugin.sql

import dev.teo.mydb.plugin.MyDbTestCase

/** SQL code completion. */
class SqlCompletionTest : MyDbTestCase() {

    private fun complete(text: String, schema: String? = null): List<String> {
        sql(text, schema)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun testTableNameAfterSchemaQualifier() {
        val items = complete("select * from shop.orders o join shop.cu<caret>")
        assertTrue(items.toString(), "customers" in items && "customers_id_seq" in items)
        assertTrue("table before its sequence", items.indexOf("customers") < items.indexOf("customers_id_seq"))
    }

    fun testForeignKeyJoinConditionComesFirst() {
        val items = complete("select * from shop.orders o join shop.customers c on <caret>")
        assertEquals("o.customer_id = c.id", items.first())
        assertTrue(items.toString(), "o" in items && "c" in items)
        assertTrue(items.toString(), "c.email" in items)
    }

    fun testVirtualForeignKeyDrivesJoinCompletionButNotDdl() {
        // products and customers are not related in the database; a virtual key makes them joinable
        val virtual = dev.teo.mydb.plugin.model.VirtualObjects.getInstance(project)
        val vfk = dev.teo.mydb.plugin.model.VirtualObjects.VirtualFk().apply {
            dataSourceId = dataSource.id; database = "obs"; schema = "shop"; table = "products"; columns = "id"; refTable = "customers"; refColumns = "id"
        }
        virtual.add(vfk)
        try {
            val items = complete("select * from shop.products p join shop.customers c on <caret>")
            assertEquals("p.id = c.id", items.first())
            val products = dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(dataSource.id)!!.schema("obs", "shop")!!.table("products")!!
            assertTrue(products.foreignKeys.single().virtual)
            assertNull(dev.teo.mydb.plugin.model.ModelService.getInstance(project).rawModel(dataSource.id)!!.schema("obs", "shop")!!.table("products")!!.foreignKeys.firstOrNull())
            val ddl = dev.teo.mydb.core.ddl.PgDdlGenerator().table("shop", products)
            assertFalse(ddl, ddl.contains("references"))
            val edge = dev.teo.mydb.core.diagram.Diagrams.erd(dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(dataSource.id)!!.schema("obs", "shop")!!)
                .edges.single { it.from == "products" }
            assertEquals(dev.teo.mydb.core.diagram.EdgeKind.VIRTUAL, edge.kind)
        } finally { virtual.remove(dataSource.id, "products", vfk.name) }
        assertNull(dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(dataSource.id)!!.schema("obs", "shop")!!.table("products")!!.foreignKeys.firstOrNull())
    }

    fun testColumnsInWhere() {
        // a single candidate is inserted right away
        val items = complete("select * from shop.orders where sta<caret>")
        assertTrue(items.toString(), items.contains("status") || myFixture.editor.document.text.endsWith("where status"))
    }

    fun testColumnsInOrderByAfterWhere() {
        val items = complete("select * from shop.orders o where o.status = 'x' order by cust<caret>")
        assertTrue(items.toString(), items.contains("customer_id") || myFixture.editor.document.text.endsWith("order by customer_id"))
    }

    fun testColumnsInEmptyOrderBy() {
        val items = complete("select * from shop.orders order by <caret>")
        assertTrue(items.toString(), items.contains("ordered_at"))
    }

    fun testReverseForeignKeyDirection() {
        val items = complete("select * from shop.customers c join shop.orders o on <caret>")
        assertEquals("o.customer_id = c.id", items.first())
    }

    fun testColumnsAfterAliasQualifier() {
        val items = complete("select o.<caret> from shop.orders o")
        assertEquals(setOf("id", "customer_id", "status", "ordered_at"), items.toSet())
    }

    fun testSingleTableColumnsAreUnqualified() {
        val items = complete("select st<caret> from shop.orders")
        assertTrue(items.toString(), "status" in items)
    }

    fun testUnqualifiedTablesFromConsoleSchema() {
        val items = complete("select * from ord<caret>", schema = "shop")
        assertTrue(items.toString(), "orders" in items && "order_items" in items && "order_totals" in items)
    }

    fun testInsertColumnList() {
        val items = complete("insert into shop.products (<caret>) values (1)")
        assertEquals(setOf("id", "sku", "title", "price"), items.toSet())
    }

    fun testCteNamesInFrom() {
        complete("with recent as (select 1 as x) select * from rec<caret>")
        // the only candidate is inserted directly
        assertTrue(myFixture.editor.document.text.endsWith("from recent"))
    }
}
