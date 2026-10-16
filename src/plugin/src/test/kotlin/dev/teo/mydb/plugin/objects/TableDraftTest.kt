package dev.teo.mydb.plugin.objects

import dev.teo.mydb.plugin.TestDatabase
import junit.framework.TestCase

/** Create/modify dialog model: */
class TableDraftTest : TestCase() {
    private val shop get() = TestDatabase.model.schema("obs", "shop")!!

    /** reviews(id ID, product_id not null, body text) + PK + FK. */
    private fun reviews(): TableDraft = TableDraft(shop, null).apply {
        name = "reviews"
        addColumn().apply { name = "id"; identity = true }
        addColumn().apply { name = "product_id"; notNull = true }
        addColumn().apply { name = "body"; type = "text" }
        addPrimaryKey("id")
        addForeignKey().apply { refTable = "products"; pairs += "product_id" to "id" }
        refreshAutoNames()
    }

    fun `test create table preview matches the expected layout`() {
        assertEquals(emptyList<String>(), reviews().problems())
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
            reviews().preview(),
        )
    }

    fun `test foreign key name follows target table and columns`() {
        val d = TableDraft(shop, null).apply { name = "reviews"; addColumn().name = "product_id" }
        val fk = d.addForeignKey()
        assertEquals("reviews___fk", fk.name)
        fk.refTable = "products"; d.refreshAutoNames()
        assertEquals("reviews_products__fk", fk.name)
        fk.pairs += "product_id" to "id"; d.refreshAutoNames()
        assertEquals("reviews_products_id_fk", fk.name)
        d.renameForeignKey(fk, "my_fk")
        d.name = "reviews_x"; d.refreshAutoNames()
        assertEquals("a name typed by the user is kept", "my_fk", fk.name)
    }

    fun `test incomplete foreign key is reported`() {
        val d = reviews()
        d.addForeignKey()
        assertTrue(d.problems().toString(), d.problems().size >= 2)
    }

    fun `test modify adds one column with one statement`() {
        val d = TableDraft(shop, shop.table("products"))
        d.addColumn().apply { name = "rating"; default = "5"; notNull = true }
        assertEquals(listOf("alter table shop.products\n    add rating integer default 5 not null;"), d.statements())
    }

    fun `test renaming the table renames automatic constraint names first`() {
        val created = reviews().toDbTable()
        val d = TableDraft(shop.copy(tables = shop.tables + created), created)
        d.name = "reviews_2"; d.refreshAutoNames()
        assertEquals(
            listOf(
                "alter table shop.reviews\n    rename constraint reviews_pk to reviews_2_pk;",
                "alter table shop.reviews\n    rename constraint reviews_products_id_fk to reviews_2_products_id_fk;",
                "alter table shop.reviews\n    rename to reviews_2;",
            ),
            d.statements(),
        )
    }

    fun `test unchanged table produces no statements and renamed column one rename`() {
        val d = TableDraft(shop, shop.table("customers"))
        assertEquals(emptyList<String>(), d.statements())
        d.columns.single { it.name == "email" }.name = "contact_email"
        assertEquals(listOf("alter table shop.customers\n    rename column email to contact_email;"), d.statements())
    }
}
