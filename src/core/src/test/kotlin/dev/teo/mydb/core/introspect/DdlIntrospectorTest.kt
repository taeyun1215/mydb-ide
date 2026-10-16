package dev.teo.mydb.core.introspect

import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.model.FkAction
import dev.teo.mydb.core.model.RelationKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DDL data sources: a model read from SQL files. */
class DdlIntrospectorTest {
    private val ddl = """
        -- the shop
        create schema shop;
        create table shop.customers
        (
            id    serial primary key,
            name  text not null,
            email varchar(200) unique,
            created_at timestamp with time zone default now() not null
        );
        create table shop.orders (
            id integer generated always as identity,
            customer_id integer not null references shop.customers on delete cascade,
            status text default 'new',
            total numeric(10, 2) check (total >= 0),
            constraint orders_pk primary key (id)
        );
        create index orders_status_idx on shop.orders (status) where status <> 'done';
        create view shop.open_orders as select id from shop.orders where status = 'new';
        comment on table shop.orders is 'customer''s orders';
        alter table shop.orders owner to app;
        create table plain (x int);
        insert into plain values (1);
    """.trimIndent()

    @Test
    fun `tables, keys, indexes and views from DDL`() {
        val m = DdlIntrospector().parse("ddl", ddl)
        val shop = m.databases.single().schema("shop")!!
        val customers = shop.table("customers")!!
        assertEquals(listOf("id"), customers.primaryKey?.columns)
        assertTrue(customers.column("id")!!.isSerial)
        assertEquals("integer", customers.column("id")!!.type)
        assertEquals(listOf("email"), customers.uniqueKeys.single().columns)
        assertEquals("timestamp with time zone", customers.column("created_at")!!.type)
        assertEquals("now()", customers.column("created_at")!!.default)
        val orders = shop.table("orders")!!
        assertEquals("orders_pk", orders.primaryKey?.name)
        assertEquals(FkAction.CASCADE, orders.foreignKeys.single().onDelete)
        assertEquals("customers", orders.foreignKeys.single().refTable)
        assertEquals("'new'", orders.column("status")!!.default)
        assertEquals("numeric(10,2)", orders.column("total")!!.type)
        assertEquals("(total >= 0)", orders.checks.single().expression)
        assertEquals("orders_status_idx", orders.indexes.single().name)
        assertEquals("status <> 'done'", orders.indexes.single().predicate)
        assertEquals("customer's orders", orders.comment)
        assertEquals("app", orders.owner)
        assertEquals(RelationKind.VIEW, shop.table("open_orders")!!.kind)
        assertEquals(listOf("x"), m.databases.single().schema("public")!!.table("plain")!!.columns.map { it.name })
        assertEquals("integer", m.databases.single().schema("public")!!.table("plain")!!.columns.single().type)
    }

    @Test
    fun `a folder reads every sql file`() {
        val dir = Files.createTempDirectory("ddl")
        Files.writeString(dir.resolve("a.sql"), "create table a (id int primary key);")
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub/b.sql"), "create table b (a_id int references a(id));")
        Files.writeString(dir.resolve("notes.txt"), "create table ignored (x int);")
        val m = Introspector.forDbms(Dbms.DDL).introspect(DataSourceConfig(name = "d", dbms = Dbms.DDL, url = "ddl:$dir"), null)
        val pub = m.databases.single().schema("public")!!
        assertEquals(listOf("a", "b"), pub.tables.map { it.name })
        assertEquals(listOf("id"), pub.table("b")!!.foreignKeys.single().refColumns)
    }
}
