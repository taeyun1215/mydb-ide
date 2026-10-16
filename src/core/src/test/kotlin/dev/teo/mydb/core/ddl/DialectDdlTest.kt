package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.exec.SqlDialect
import dev.teo.mydb.core.introspect.Introspector
import dev.teo.mydb.core.model.*
import dev.teo.mydb.core.mysql.MysqlAcceptanceTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Create/modify table DDL for SQLite and MySQL, executed against real servers. */
class DialectDdlTest {
    private val reviews = DbTable(0, "reviews", RelationKind.TABLE, listOf(
        DbColumn("id", 1, "integer", true, identity = IdentityKind.BY_DEFAULT),
        DbColumn("product", 2, "varchar(100)", true),
        DbColumn("body", 3, "text", false, "'none'"),
    ), primaryKey = DbKey("reviews_pk", listOf("id"), true),
        indexes = listOf(DbIndex("reviews_product_index", listOf("product"), false)))

    @Test
    fun `SQLite create and alter run on the file`() {
        val f = Files.createTempFile("ddl", ".sqlite")
        val g = DialectDdl(SqlDialect.SQLITE, "main")
        val create = g.create(reviews)
        assertEquals("create table reviews\n(\n    id integer primary key autoincrement,\n    product varchar(100) not null,\n    body text default 'none'\n);", create.first())
        DriverManager.getConnection("jdbc:sqlite:$f").use { c -> create.forEach { c.createStatement().execute(it) } }
        val cfg = DataSourceConfig(name = "f", dbms = Dbms.SQLITE, url = "jdbc:sqlite:$f")
        val stored = Introspector.forDbms(Dbms.SQLITE).introspect(cfg, null).databases.single().schemas.single().table("reviews")!!
        val changed = stored.copy(name = "reviews2", columns = stored.columns + DbColumn("stars", 4, "integer", false, "5"))
        val g2 = DialectDdl(SqlDialect.SQLITE, "main")
        val alter = g2.alter(stored, changed)
        assertEquals(listOf("alter table reviews rename to reviews2;", "alter table reviews2 add stars integer default 5;"), alter)
        DriverManager.getConnection("jdbc:sqlite:$f").use { c -> alter.forEach { c.createStatement().execute(it) } }
        assertTrue(g2.unsupported.isEmpty())
        // a type change needs a rebuild: reported, not generated
        val g3 = DialectDdl(SqlDialect.SQLITE, "main")
        g3.alter(stored, stored.copy(columns = stored.columns.map { if (it.name == "body") it.copy(type = "blob") else it }))
        assertEquals(listOf("SQLite cannot change column body; the table must be rebuilt"), g3.unsupported)
    }

    @Test
    fun `MySQL create and alter run on the server`() {
        val server = MysqlAcceptanceTest.server
        // a database of its own, so the shop schema of the other tests stays as seeded
        DriverManager.getConnection(server.jdbcUrl, "root", "obs").use { c ->
            c.createStatement().execute("create database if not exists ddl"); c.createStatement().execute("grant all on ddl.* to 'obs'@'%'")
        }
        val url = "jdbc:mariadb://${server.host}:${server.firstMappedPort}/ddl"
        val g = DialectDdl(SqlDialect.MYSQL, "ddl")
        val create = g.create(reviews.copy(columns = reviews.columns.map { if (it.name == "id") it.copy(type = "int") else it }, comment = "user reviews"))
        assertEquals("create table ddl.reviews\n(\n    id int not null auto_increment primary key,\n    product varchar(100) not null,\n    body text default 'none'\n) comment 'user reviews';", create.first())
        assertEquals("create index reviews_product_index on ddl.reviews (product);", create[1])
        DriverManager.getConnection(url, "obs", "obs").use { c -> create.forEach { c.createStatement().execute(it) } }
        val cfg = DataSourceConfig(name = "m", dbms = Dbms.MYSQL, url = "jdbc:mysql://${server.host}:${server.firstMappedPort}/ddl", user = "obs")
        val stored = Introspector.forDbms(Dbms.MYSQL).introspect(cfg, "obs").databases.single().schema("ddl")!!.table("reviews")!!
        val changed = stored.copy(columns = stored.columns.map { if (it.name == "product") it.copy(type = "varchar(200)") else it } + DbColumn("stars", 4, "int", true, "5"))
        val alter = DialectDdl(SqlDialect.MYSQL, "ddl").alter(stored, changed)
        assertEquals(listOf("alter table ddl.reviews modify product varchar(200) not null;", "alter table ddl.reviews add stars int default 5 not null;"), alter)
        DriverManager.getConnection(url, "obs", "obs").use { c -> alter.forEach { c.createStatement().execute(it) } }
        val after = Introspector.forDbms(Dbms.MYSQL).introspect(cfg, "obs").databases.single().schema("ddl")!!.table("reviews")!!
        assertEquals("varchar(200)", after.column("product")!!.type)
        assertEquals("5", after.column("stars")!!.default)
    }

    @Test
    fun `SQLite schema compare writes SQLite DDL`() {
        val a = Files.createTempFile("a", ".sqlite"); val b = Files.createTempFile("b", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:$a").use { c -> c.createStatement().use { st ->
            st.execute("create table t (id integer primary key, name text, extra text)"); st.execute("create table only_a (x integer)") } }
        DriverManager.getConnection("jdbc:sqlite:$b").use { c -> c.createStatement().use { st ->
            st.execute("create table t (id integer primary key, name text)"); st.execute("create table only_b (y integer)") } }
        fun schema(f: java.nio.file.Path) = Introspector.forDbms(Dbms.SQLITE).introspect(DataSourceConfig(name = "x", dbms = Dbms.SQLITE, url = "jdbc:sqlite:$f"), null)
            .databases.single().schemas.single()
        val diff = dev.teo.mydb.core.diff.SchemaDiff(schema(a), schema(b), dialect = SqlDialect.SQLITE)
        val script = diff.script()
        assertTrue(script.contains("alter table t add extra text;"), script)
        assertTrue(script.contains("create table only_a"), script)
        assertTrue(script.contains("drop table only_b;"), script)
        DriverManager.getConnection("jdbc:sqlite:$b").use { c -> script.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { c.createStatement().execute(it) } }
        val again = dev.teo.mydb.core.diff.SchemaDiff(schema(a), schema(b), dialect = SqlDialect.SQLITE)
        assertTrue(again.items.all { it.status == dev.teo.mydb.core.diff.DiffStatus.SAME }, again.items.toString())
    }
}
