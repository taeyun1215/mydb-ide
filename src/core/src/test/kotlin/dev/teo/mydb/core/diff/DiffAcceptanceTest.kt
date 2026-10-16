package dev.teo.mydb.core.diff

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.core.ddl.ConstraintNaming
import dev.teo.mydb.core.ddl.PgAlterGenerator
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.core.model.DbColumn
import dev.teo.mydb.core.model.DbModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 04 (DDL part). */
class DiffAcceptanceTest {
    /** A diverging copy `shop2` of the seed schema. */
    private val shop2 = """
        create schema shop2;
        create table shop2.customers (id serial primary key, name varchar(100) not null, email text, phone text);
        create table shop2.products (id serial primary key, sku varchar(32) not null unique, title text not null, price numeric(12,2) not null);
        create table shop2.legacy (x int);
    """.trimIndent()

    private fun setup(): Pair<DataSourceConfig, DbModel> {
        val cfg = SeededPostgres.freshDatabase()
        SeededPostgres.connect(cfg) { it.createStatement().execute(shop2) }
        val db = cfg.url.substringAfterLast('/')
        val scoped = cfg.copy(introspectionScope = cfg.introspectionScope.plus(SchemaRef(db, "shop")).plus(SchemaRef(db, "shop2")))
        return scoped to PostgresIntrospector().introspect(scoped, SeededPostgres.PASSWORD)
    }

    private fun DbModel.s(name: String) = databases.first { it.isDefault }.schema(name)!!

    @Test
    fun `comparison tree statuses`() {
        val (_, m) = setup()
        val items = SchemaDiff(m.s("shop"), m.s("shop2")).items.associateBy { it.name }
        assertEquals(DiffStatus.CHANGED, items["customers"]!!.status)
        assertEquals(DiffStatus.ONLY_IN_TARGET, items["legacy"]!!.status)
        assertEquals(DiffStatus.ONLY_IN_SOURCE, items["orders"]!!.status)
        val cols = items["customers"]!!.children.associateBy { it.name }
        assertEquals(DiffStatus.CHANGED, cols["name"]!!.status)
        assertEquals(DiffStatus.ONLY_IN_TARGET, cols["phone"]!!.status)
        assertEquals(DiffStatus.ONLY_IN_SOURCE, cols["created_at"]!!.status)
    }

    @Test
    fun `script follows the phase order`() {
        val (_, m) = setup()
        val script = SchemaDiff(m.s("shop"), m.s("shop2")).script()
        fun at(s: String) = script.indexOf(s).also { assertTrue(it >= 0, "missing: $s\n$script") }
        val order = listOf(
            "alter table shop2.customers\n    add created_at timestamp with time zone default now() not null;",
            "alter table shop2.customers\n    add unique (email);",
            "alter table shop2.products\n    add constraint products_price_check",
            "create table shop2.orders",
            "create table shop2.order_items",
            "alter table shop2.customers\n    alter column name type text using name::text;",
            "alter table shop2.customers\n    drop column phone;",
            // column changes stay grouped per table: customers' drop comes before products' type change
            "alter table shop2.products\n    alter column price type numeric(10, 2)",
            "drop table shop2.legacy;",
            "create view shop2.order_totals",
        ).map(::at)
        assertEquals(order.sorted(), order, script)
        assertTrue("references shop2.customers" in script)
        assertTrue("create index orders_customer_idx\n    on shop2.orders (customer_id);" in script)
        assertTrue("alter column price type numeric(10, 2) using price::numeric(10, 2);" in script)
    }

    @Test
    fun `unchecked items are left out`() {
        val (_, m) = setup()
        val d = SchemaDiff(m.s("shop"), m.s("shop2"))
        val all = d.items.flatMap { it.flatten() }.map { it.id }.toSet()
        assertTrue("drop table shop2.legacy" !in d.script(all - "table:legacy"))
    }

    @Test
    fun `running the script makes the schemas equal`() {
        val (cfg, m) = setup()
        val script = SchemaDiff(m.s("shop"), m.s("shop2")).script()
        SeededPostgres.connect(cfg) { it.createStatement().execute(script) }
        val again = PostgresIntrospector().introspect(cfg, SeededPostgres.PASSWORD)
        val remaining = SchemaDiff(again.s("shop"), again.s("shop2")).items.flatMap { it.flatten() }
            .filter { it.status != DiffStatus.SAME }
        assertEquals(emptyList(), remaining.map { it.name }, remaining.toString())
    }

    @Test
    fun `swapping direction`() {
        val (_, m) = setup()
        val script = SchemaDiff(m.s("shop2"), m.s("shop")).script()
        assertTrue("create table shop.legacy" in script)
        assertTrue("drop table shop.orders" in script || "drop table shop.order_items" in script)
    }

    // ---- modify dialog ----

    @Test
    fun `adding a column`() {
        val (_, m) = setup()
        val products = m.s("shop").table("products")!!
        val edited = products.copy(columns = products.columns + DbColumn("rating", 5, "integer", notNull = true, default = "5"))
        assertEquals(listOf("alter table shop.products\n    add rating integer default 5 not null;"), PgAlterGenerator("shop").alter(products, edited))
    }

    @Test
    fun `renaming a table renames automatic constraint names first`() {
        val (_, m) = setup()
        val base = m.s("shop").table("products")!!
        val reviews = base.copy(
            name = "reviews",
            primaryKey = base.primaryKey!!.copy(name = ConstraintNaming.primaryKey("reviews")),
            foreignKeys = listOf(dev.teo.mydb.core.model.DbForeignKey(ConstraintNaming.foreignKey("reviews", "products", listOf("id")), listOf("id"), "shop", "products", listOf("id"))),
        )
        val renamed = reviews.copy(
            name = "reviews_2",
            primaryKey = reviews.primaryKey!!.copy(name = ConstraintNaming.primaryKey("reviews_2")),
            foreignKeys = reviews.foreignKeys.map { it.copy(name = ConstraintNaming.foreignKey("reviews_2", "products", listOf("id"))) },
        )
        assertEquals(
            listOf(
                "alter table shop.reviews\n    rename constraint reviews_pk to reviews_2_pk;",
                "alter table shop.reviews\n    rename constraint reviews_products_id_fk to reviews_2_products_id_fk;",
                "alter table shop.reviews\n    rename to reviews_2;",
            ),
            PgAlterGenerator("shop").alter(reviews, renamed, PgAlterGenerator.Renames(table = "reviews_2")),
        )
    }

    // ---- rename refactoring DDL ----

    @Test
    fun `rename ddl`() {
        val g = PgAlterGenerator("shop")
        assertEquals("alter table shop.reviews\n    rename column body to content;", g.renameColumn("reviews", "body", "content"))
        assertEquals("alter table shop.reviews\n    rename to reviews_v2;", g.renameTable("reviews", "reviews_v2"))
    }
}
