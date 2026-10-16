package dev.teo.mydb.plugin.sql

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.sql.psi.DbObjectElement
import dev.teo.mydb.plugin.sql.psi.SqlColumnRef
import dev.teo.mydb.plugin.sql.psi.SqlTableRef

/** plus parser robustness on sample queries. */
class SqlHighlightingTest : MyDbTestCase() {

    private fun errors() = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.text to it.description }

    fun testSampleQueriesParseWithoutErrors() {
        val queries = listOf(
            "select * from shop.orders o join shop.customers c on o.customer_id = c.id where o.id <= 20",
            "select o.id, c.name, sum(i.qty) from shop.orders o join shop.customers c on c.id = o.customer_id join shop.order_items i on i.order_id = o.id where o.status = 'paid' group by o.id, c.name",
            "select * from shop.customers where id < 30;\nselect sku, title, price from shop.products order by price desc;",
            "update shop.products set price = price + 100 where id = 1",
            "with t as (select id from shop.orders) select * from t where id in (select order_id from shop.order_items)",
            "insert into shop.products (sku, title, price) values ('a', 'b', 1.5) returning id",
            "delete from shop.nopk where a = 2 and b is not null",
            "create table shop.reviews (id serial constraint reviews_pk primary key, product_id integer not null references shop.products, body text)",
            "select case when price > 10 then 'x' else 'y' end, cast(price as text), price::numeric(10, 2), now() - interval '1 day' from shop.products",
            "explain (format json) select 1",
            "select count(*) filter (where status = 'paid') over (partition by customer_id) from shop.orders",
        )
        for (q in queries) {
            val file = sql(q)
            val err = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { "${it.errorDescription} at ${it.textOffset}" }
            assertEquals("parse errors in: $q", emptyList<String>(), err)
        }
    }

    fun testUnresolvedColumn() {
        sql("select o.statsu from shop.orders o")
        assertEquals(listOf("statsu" to "Unknown column 'statsu'"), errors())
    }

    fun testUnresolvedSymbol() {
        sql("select x.name from shop.orders o")
        assertEquals(listOf("x" to "Cannot resolve 'x'"), errors())
    }

    fun testUnresolvedTableWithFixes() {
        sql("select * from shop.cust<caret>mers")
        assertEquals(listOf("custmers" to "Unknown table 'custmers'"), errors())
        val fixes = myFixture.availableIntentions.map { it.text }
        assertTrue(fixes.toString(), "Refresh metadata" in fixes)
        assertTrue(fixes.toString(), "Create table definition" in fixes)
    }

    fun testRefreshMetadataResolvesTableCreatedElsewhere() {
        val config = freshDataSource("refresh-ds")
        val file = myFixture.configureByText("refresh.sql", "select * from shop.late_<caret>table")
        SqlDataSourceBinding.getInstance(project).bind(file.virtualFile, config.id, "shop")
        assertEquals(listOf("late_table" to "Unknown table 'late_table'"), errors())

        java.sql.DriverManager.getConnection(config.url, "obs", dev.teo.mydb.plugin.TestDatabase.PASSWORD).use {
            it.createStatement().execute("create table shop.late_table(id int)")
        }
        val before = dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(config.id)
        myFixture.launchAction(myFixture.findSingleIntention("Refresh metadata"))
        waitFor("model refreshed") { dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(config.id) !== before }
        val db = config.url.substringAfterLast('/')
        assertNotNull("introspection found the new table", dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!.table("late_table"))
        assertEquals(emptyList<Pair<String, String>>(), errors())
    }

    fun testErrorQueryReportsThreeErrors() {
        // the same three unresolved names are reported, the dependent `c.id` is not
        sql("select o.id, o.statsu, x.name from shop.orders o join shop.custmers c on c.id = o.customer_id where o.status = 'new' group by o.id")
        assertEquals(setOf("statsu", "x", "custmers"), errors().map { it.first }.toSet())
    }

    fun testCorrectQueryHasNoErrors() {
        sql("select o.id, o.status, c.name, count(*) from shop.orders o join shop.customers c on c.id = o.customer_id where o.status = 'new' group by o.id, o.status, c.name")
        assertEquals(emptyList<Pair<String, String>>(), errors())
    }

    fun testResolveTargets() {
        val file = sql("select o.customer_id from shop.orders o")
        val col = PsiTreeUtil.findChildOfType(file, SqlColumnRef::class.java)!!
        val target = col.references.last().resolve() as DbObjectElement
        assertEquals("orders", target.table.name)
        assertEquals("customer_id", target.column!!.name)
        val table = PsiTreeUtil.findChildOfType(file, SqlTableRef::class.java)!!
        assertEquals("shop", (table.references.first().resolve() as DbObjectElement).schema)
    }

    fun testCteAndSubqueryColumnsResolve() {
        sql("with t as (select id as oid, status from shop.orders) select t.oid, x.n from t join (select count(*) as n from shop.customers) x on true")
        assertEquals(emptyList<Pair<String, String>>(), errors())
    }

    fun testUnqualifiedNamesUseConsoleSchema() {
        sql("select name from customers", schema = "shop")
        assertEquals(emptyList<Pair<String, String>>(), errors())
        sql("select name from customers") // default schema public: not found
        assertEquals(listOf("customers"), errors().map { it.first })
    }

    fun testInsertAndUpdateTargetsResolve() {
        sql("insert into shop.products (sku, title, pricee) values ('a','b',1); update shop.products set titel = 'x' where id = 1")
        assertEquals(setOf("pricee", "titel"), errors().map { it.first }.toSet())
    }

    fun testUnboundFileReportsNothing() {
        myFixture.configureByText("free.sql", "select * from nothing_here")
        assertEquals(emptyList<Pair<String, String>>(), errors())
    }
}
