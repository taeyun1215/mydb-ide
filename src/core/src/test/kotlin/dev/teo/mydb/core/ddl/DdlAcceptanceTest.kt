package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generated DDL text. */
class DdlAcceptanceTest {
    private val shop by lazy {
        val cfg = SeededPostgres.config().let { it.copy(introspectionScope = it.introspectionScope.plus(SchemaRef("obs", "shop"))) }
        PostgresIntrospector().introspect(cfg, SeededPostgres.PASSWORD).schema("obs", "shop")!!
    }
    private fun gen(options: DdlOptions = DdlOptions(contextSchema = "shop")) = PgDdlGenerator(options) { s, t -> if (s == "shop") shop.table(t) else null }

    @Test
    fun `customers ddl uses serial, inline pk and unique`() {
        assertEquals(
            """
            create table customers
            (
                id         serial
                    primary key,
                name       text                                   not null,
                email      text
                    unique,
                created_at timestamp with time zone default now() not null
            );

            alter table customers
                owner to obs;
            """.trimIndent(),
            gen().table("shop", shop.table("customers")!!),
        )
    }

    @Test
    fun `order_items ddl has inline fks and table level composite pk`() {
        assertEquals(
            """
            create table order_items
            (
                order_id   integer           not null
                    references orders
                        on delete cascade,
                product_id integer           not null
                    references products,
                qty        integer default 1 not null,
                primary key (order_id, product_id)
            );
            """.trimIndent(),
            gen(DdlOptions(contextSchema = "shop", skipOwner = true)).table("shop", shop.table("order_items")!!),
        )
    }

    @Test
    fun `multi column pk is table level`() {
        val ddl = gen().createTable("shop", shop.table("order_items")!!)
        assertTrue("    primary key (order_id, product_id)" in ddl)
    }

    @Test
    fun `named constraints keep a constraint clause`() {
        val reviews = DbTable(
            oid = 1, name = "reviews", kind = RelationKind.TABLE,
            columns = listOf(
                DbColumn("id", 1, "integer", notNull = true, default = "nextval('shop.reviews_id_seq'::regclass)"),
                DbColumn("product_id", 2, "integer", notNull = true),
                DbColumn("body", 3, "text", notNull = false),
            ),
            primaryKey = DbKey("reviews_pk", listOf("id"), primary = true),
            foreignKeys = listOf(DbForeignKey("reviews_products_id_fk", listOf("product_id"), "shop", "products", listOf("id"))),
        )
        assertEquals(
            """
            create table shop.reviews
            (
                id         serial
                    constraint reviews_pk
                        primary key,
                product_id integer not null
                    constraint reviews_products_id_fk
                        references shop.products,
                body       text
            );
            """.trimIndent(),
            gen(DdlOptions()).createTable("shop", reviews),
        )
    }

    @Test
    fun `secondary indexes, checks and types are rendered`() {
        val ddl = gen().table("shop", shop.table("orders")!!)
        // aligned column list
        assertTrue(
            """
                customer_id integer                                                   not null
                    references customers,
                status      varchar(16)              default 'new'::character varying not null,
                ordered_at  timestamp with time zone default now()                    not null
            """.trimIndent().prependIndent("    ") in ddl, ddl,
        )
        assertTrue("create index orders_customer_idx\n    on orders (customer_id);" in ddl, ddl)
        val products = gen().createTable("shop", shop.table("products")!!)
        assertTrue("price numeric(10, 2) not null" in products.replace(Regex(" +"), " "), products)
        assertTrue("constraint products_price_check\n        check (price >= (0)::numeric)" in products, products)
    }

    @Test
    fun `table level constraint placement`() {
        val ddl = gen(DdlOptions(contextSchema = "shop", constraintPlacement = ConstraintPlacement.TABLE)).createTable("shop", shop.table("customers")!!)
        assertTrue("    primary key (id)" in ddl, ddl)
        assertTrue("    unique (email)" in ddl, ddl)
    }

    @Test
    fun `if not exists`() {
        assertTrue(gen(DdlOptions(ifNotExists = true)).createTable("shop", shop.table("customers")!!).startsWith("create table if not exists shop.customers"))
    }

    @Test
    fun `generated schema script recreates the schema`() {
        val script = gen(DdlOptions(targetSchema = "copy1", skipOwner = true)).schemaScript(shop)
        SeededPostgres.connect(SeededPostgres.freshDatabase()) { c ->
            c.createStatement().execute(script)
            c.createStatement().executeQuery("select count(*) from information_schema.tables where table_schema = 'copy1'").use { rs ->
                rs.next(); assertEquals(5, rs.getInt(1)) // 4 tables + 1 view
            }
        }
    }

    @Test
    fun `quoting of identifiers`() {
        assertEquals("\"user\"", PgDdlGenerator.quote("user"))
        assertEquals("\"Mixed\"", PgDdlGenerator.quote("Mixed"))
        assertEquals("plain_1", PgDdlGenerator.quote("plain_1"))
    }
}
