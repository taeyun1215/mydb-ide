package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.core.model.FkAction
import dev.teo.mydb.core.model.ModelStore
import dev.teo.mydb.core.model.RelationKind
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PostgreSQL introspection. */
class IntrospectionAcceptanceTest {
    private val pw = SeededPostgres.PASSWORD

    @Test
    fun `first introspection covers only the default schema`() {
        val cfg = SeededPostgres.config()
        val model = PostgresIntrospector().introspect(cfg, pw)
        val db = model.database("obs")!!
        assertEquals(listOf("public"), db.schemas.map { it.name })
        assertTrue("shop" in db.allSchemaNames)
        // badge "shown/total" for the database node: 1 of 2 user schemas (system schemas excluded)
        val userSchemas = db.allSchemaNames.filterNot { it.startsWith("pg_") || it == "information_schema" }
        assertEquals(1 to 2, db.schemas.size to userSchemas.size)
    }

    @Test
    fun `adding shop to the scope loads its objects`() {
        val cfg = SeededPostgres.config().let { it.copy(introspectionScope = it.introspectionScope.plus(SchemaRef("obs", "shop"))) }
        val shop = PostgresIntrospector().introspect(cfg, pw).schema("obs", "shop")!!
        val byKind = shop.relationsByKind
        assertEquals(listOf("customers", "order_items", "orders", "products"), byKind[RelationKind.TABLE]!!.map { it.name })
        assertEquals(listOf("order_totals"), byKind[RelationKind.VIEW]!!.map { it.name })
        assertEquals(3, shop.sequences.size)
        assertNotNull(shop.table("order_totals")!!.viewDefinition)
    }

    @Test
    fun `table details`() {
        val orders = shopModel().schema("obs", "shop")!!.table("orders")!!
        assertEquals(listOf("id", "customer_id", "status", "ordered_at"), orders.columns.map { it.name })
        val id = orders.column("id")!!
        assertEquals("integer", id.type)
        assertTrue(id.default!!.startsWith("nextval("))
        assertEquals("character varying(16)", orders.column("status")!!.type)
        assertEquals(listOf("id"), orders.primaryKey!!.columns)
        assertEquals(1, orders.foreignKeys.size)
        assertEquals("customers", orders.foreignKeys.single().refTable)
        assertEquals(listOf("id"), orders.foreignKeys.single().refColumns)
        assertEquals(2, orders.indexes.size) // pkey + orders_customer_idx
        assertEquals(1, orders.indexes.count { !it.backsConstraint })
    }

    @Test
    fun `order_items has composite pk and cascade fk`() {
        val oi = shopModel().schema("obs", "shop")!!.table("order_items")!!
        assertEquals(listOf("order_id", "product_id"), oi.primaryKey!!.columns)
        assertEquals(FkAction.CASCADE, oi.foreignKeys.first { it.refTable == "orders" }.onDelete)
        val products = shopModel().schema("obs", "shop")!!.table("products")!!
        assertEquals("(price >= (0)::numeric)", products.checks.single().expression)
        assertEquals(listOf("sku"), products.uniqueKeys.single().columns)
    }

    @Test
    fun `empty schema has no objects`() {
        assertTrue(shopModel().schema("obs", "public")!!.tables.isEmpty())
    }

    @Test
    fun `refresh scope follows the selected node`() {
        val cfg = shopConfig(SeededPostgres.freshDatabase())
        val db = dbName(cfg.url)
        val i = PostgresIntrospector()
        val first = i.introspect(cfg, pw)
        SeededPostgres.connect(cfg) { it.createStatement().execute("create table shop.audit_log(id bigserial primary key, msg text)") }

        val partial = i.introspect(cfg, pw, first, RefreshTarget.Tables(db, "shop", setOf("orders")))
        assertNull(partial.schema(db, "shop")!!.table("audit_log"))

        val full = i.introspect(cfg, pw, partial, RefreshTarget.All)
        assertNotNull(full.schema(db, "shop")!!.table("audit_log"))
        assertEquals(5, full.schema(db, "shop")!!.relationsByKind[RelationKind.TABLE]!!.size)
    }

    @Test
    fun `second introspection only reads changed relations`() {
        val cfg = shopConfig(SeededPostgres.freshDatabase())
        val db = dbName(cfg.url)
        val i = PostgresIntrospector()
        val first = i.introspect(cfg, pw)
        val firstRead = i.lastStats.relationsRead
        SeededPostgres.connect(cfg) { it.createStatement().execute("alter table shop.products add column weight numeric") }
        val second = i.introspect(cfg, pw, first)
        assertTrue(i.lastStats.relationsRead < firstRead, "read ${i.lastStats.relationsRead} of $firstRead")
        assertEquals(1, i.lastStats.relationsRead)
        assertNotNull(second.schema(db, "shop")!!.table("products")!!.column("weight"))
        // untouched relations are carried over
        assertEquals(first.schema(db, "shop")!!.table("customers"), second.schema(db, "shop")!!.table("customers"))
    }

    @Test
    fun `dropped relations disappear on incremental refresh`() {
        val cfg = shopConfig(SeededPostgres.freshDatabase())
        val db = dbName(cfg.url)
        val i = PostgresIntrospector()
        val first = i.introspect(cfg, pw)
        SeededPostgres.connect(cfg) { it.createStatement().execute("drop view shop.order_totals") }
        assertNull(i.introspect(cfg, pw, first).schema(db, "shop")!!.table("order_totals"))
    }

    @Test
    fun `introspection does not block a busy console session`() {
        val cfg = shopConfig(SeededPostgres.freshDatabase())
        SeededPostgres.connect(cfg) { console ->
            console.autoCommit = false
            console.createStatement().execute("update shop.products set price = price + 1 where id = 1") // open tx holding row locks
            val run = CompletableFuture.supplyAsync { PostgresIntrospector().introspect(cfg, pw) }
            assertNotNull(run.get(30, TimeUnit.SECONDS).schema(dbName(cfg.url), "shop"))
            console.rollback()
        }
    }

    @Test
    fun `model survives a restart through the store`() {
        val dir = Files.createTempDirectory("models")
        val model = shopModel()
        ModelStore(dir).save(model)
        assertEquals(model, ModelStore(dir).load(model.dataSourceId))
    }

    private fun shopConfig(base: dev.teo.mydb.core.datasource.DataSourceConfig) =
        base.copy(introspectionScope = base.introspectionScope.plus(SchemaRef(dbName(base.url), "shop")))

    private fun shopModel() = PostgresIntrospector().introspect(shopConfig(SeededPostgres.config()), pw)

    private fun dbName(url: String) = url.substringAfterLast('/')
}
