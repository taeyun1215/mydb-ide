package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.core.model.DbCheck
import dev.teo.mydb.core.model.DbIndex
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** (other kinds in the New popup) and (indexes, checks, generated columns). */
class ObjectDdlTest {
    private fun run(cfg: dev.teo.mydb.core.datasource.DataSourceConfig, statements: List<String>) =
        DriverManager.getConnection(cfg.url, cfg.user, SeededPostgres.PASSWORD).use { c -> statements.forEach { c.createStatement().execute(it) } }

    private fun shop(cfg: dev.teo.mydb.core.datasource.DataSourceConfig) =
        PostgresIntrospector().introspect(cfg.copy(introspectionScope = cfg.introspectionScope.plus(SchemaRef(cfg.url.substringAfterLast('/'), "shop"))),
            SeededPostgres.PASSWORD).schema(cfg.url.substringAfterLast('/'), "shop")!!

    @Test
    fun `schema, view and sequence scripts`() {
        assertEquals(listOf("create schema sales;", "comment on schema sales is 'Sales data';"),
            PgObjectDdl.schema(SchemaDraft("sales", comment = "Sales data")))
        assertEquals(listOf("create view shop.cheap as\nselect * from shop.products where price < 5;"),
            PgObjectDdl.view(ViewDraft("shop", "cheap", "select * from shop.products where price < 5;\n")))
        assertEquals(listOf("create materialized view shop.m as\nselect 1;"), PgObjectDdl.view(ViewDraft("shop", "m", "select 1", materialized = true)))
        assertEquals(listOf("create sequence shop.ticket_seq\n    as bigint\n    increment by 10\n    start with 100\n    cycle;"),
            PgObjectDdl.sequence(SequenceDraft("shop", "ticket_seq", type = "bigint", start = 100, increment = 10, cycle = true)))
        assertEquals(listOf("Name is empty", "Increment must not be zero"), PgObjectDdl.sequenceProblems(SequenceDraft("shop", "", increment = 0)))
    }

    @Test
    fun `scripts run on the server and the objects are introspected`() {
        val cfg = SeededPostgres.freshDatabase()
        run(cfg, PgObjectDdl.view(ViewDraft("shop", "cheap", "select id, title from shop.products where price < 5")) +
            PgObjectDdl.sequence(SequenceDraft("shop", "ticket_seq", start = 100)) +
            PgObjectDdl.schema(SchemaDraft("sales")))
        val s = shop(cfg)
        assertTrue(s.table("cheap")!!.isView)
        assertTrue(s.sequences.any { it.name == "ticket_seq" })
        val dbName = cfg.url.substringAfterLast('/')
        assertTrue("sales" in PostgresIntrospector().listSchemas(cfg, SeededPostgres.PASSWORD, dbName))
    }

    @Test
    fun `check expressions get parentheses only when needed`() {
        assertEquals("(price >= 0)", PgDdlGenerator.parenthesized("price >= 0"))
        assertEquals("(price >= (0)::numeric)", PgDdlGenerator.parenthesized("(price >= (0)::numeric)"))
        assertEquals("((a > 0) and (b > 0))", PgDdlGenerator.parenthesized("(a > 0) and (b > 0)"))
    }

    @Test
    fun `generated column, index and check round trip through the server`() {
        val cfg = SeededPostgres.freshDatabase()
        run(cfg, listOf("alter table shop.order_items add qty2 integer generated always as (qty * 2) stored"))
        val items = shop(cfg).table("order_items")!!
        val qty2 = items.column("qty2")!!
        assertTrue(qty2.generated)
        val ddl = PgDdlGenerator(DdlOptions(contextSchema = "shop", skipOwner = true)) { _, t -> null }.createTable("shop", items)
        assertTrue(ddl.contains("qty2       integer generated always as (qty * 2) stored,"), ddl)

        // ALTERs: add an index and a check, then rename and drop them
        val products = shop(cfg).table("products")!!
        val added = products.copy(
            indexes = products.indexes + DbIndex("products_title_index", listOf("title"), unique = false),
            checks = products.checks + DbCheck("products_title_check", "length(title) > 0"),
        )
        val alter = PgAlterGenerator("shop").alter(products, added)
        assertEquals(listOf(
            "alter table shop.products\n    add constraint products_title_check check (length(title) > 0);",
            "create index products_title_index\n    on shop.products (title);",
        ), alter)
        run(cfg, alter)
        val stored = shop(cfg).table("products")!!
        assertTrue(stored.indexes.any { it.name == "products_title_index" })
        assertTrue(stored.checks.any { it.name == "products_title_check" })

        val renamed = stored.copy(
            indexes = stored.indexes.map { if (it.name == "products_title_index") it.copy(name = "products_t_idx") else it },
            checks = stored.checks.filter { it.name != "products_title_check" },
        )
        val alter2 = PgAlterGenerator("shop").alter(stored, renamed, PgAlterGenerator.Renames(indexes = mapOf("products_title_index" to "products_t_idx")))
        assertEquals(listOf(
            "alter table shop.products\n    drop constraint products_title_check;",
            "alter index shop.products_title_index\n    rename to products_t_idx;",
        ), alter2)
        run(cfg, alter2)
        val after = shop(cfg).table("products")!!
        assertTrue(after.indexes.any { it.name == "products_t_idx" })
        assertTrue(after.checks.none { it.name == "products_title_check" })
    }
}
