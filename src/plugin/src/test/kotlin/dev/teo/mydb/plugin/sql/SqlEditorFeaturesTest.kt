package dev.teo.mydb.plugin.sql

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.sql.hints.JoinCardinality
import dev.teo.mydb.plugin.sql.inspections.SqlMissingWhereInspection
import dev.teo.mydb.plugin.sql.inspections.SqlNonAggregatedColumnInspection
import dev.teo.mydb.plugin.sql.psi.SqlJoinClause
import java.io.File

/** formatter, inspections, intentions, join hints. */
class SqlEditorFeaturesTest : MyDbTestCase() {

    private fun reformat(text: String): String {
        sql(text)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.editor.document.text
    }

    fun testFormatMatchesExpectedOutput() {
        val observed = File(System.getProperty("mydb.seedDir"), "../expected/reformatted.sql").readText().trimEnd()
        val input = "select o.id, o.status, c.name, count(*) from shop.orders o join shop.customers c on c.id = o.customer_id where o.status = 'new' group by o.id, o.status, c.name"
        assertEquals(observed, reformat(input).trimEnd())
    }

    fun testFormatKeepsStatementsApartAndTightensPunctuation() {
        assertEquals("select a.b, c\nfrom t a;\nselect count(*)\nfrom u;",
            reformat("select a . b ,c   from t a;select count ( * ) from u;"))
    }

    fun testFormatCreateTablePutsElementsOnLinesWithAlignedTypes() {
        assertEquals("create table notes\n(\n    id         serial primary key,\n    body       text not null,\n    created_at timestamp default now(),\n    unique (body)\n);",
            reformat("create table notes (id serial primary key, body text not null,created_at timestamp default now(), unique(body));"))
    }

    fun testWrapTableInSubquery() {
        sql("select * from shop.orders o jo<caret>in shop.customers c on c.id = o.customer_id")
        assertTrue(myFixture.filterAvailableIntentions("Wrap in subquery").isEmpty())
        sql("select * from shop.ord<caret>ers o join shop.customers c on c.id = o.customer_id")
        myFixture.launchAction(myFixture.findSingleIntention("Wrap in subquery"))
        assertEquals("select * from (select * from shop.orders) o join shop.customers c on c.id = o.customer_id", myFixture.editor.document.text)
        sql("select * from shop.cust<caret>omers")
        myFixture.launchAction(myFixture.findSingleIntention("Wrap in subquery"))
        assertEquals("select * from (select * from shop.customers) customers", myFixture.editor.document.text)
        sql("delete from shop.cust<caret>omers where id = 1")
        assertTrue(myFixture.filterAvailableIntentions("Wrap in subquery").isEmpty())
    }

    fun testDeleteWithoutWhere() {
        myFixture.enableInspections(SqlMissingWhereInspection())
        sql("delete from shop.orders; delete from shop.orders where id = 1; update shop.products set price = 1")
        val warnings = myFixture.doHighlighting().filter { it.description?.contains("without WHERE") == true }
        assertEquals(2, warnings.size)
    }

    fun testNonAggregatedColumn() {
        myFixture.enableInspections(SqlNonAggregatedColumnInspection())
        sql("select o.id, c.name, count(*) from shop.orders o join shop.customers c on c.id = o.customer_id group by o.id")
        val warnings = myFixture.doHighlighting().filter { it.description?.contains("GROUP BY") == true }.map { it.text }
        assertEquals(listOf("c.name"), warnings)
    }

    fun testGroupingByPrimaryKeyAllowsOtherColumns() {
        myFixture.enableInspections(SqlNonAggregatedColumnInspection())
        sql("select c.id, c.name, count(*) from shop.customers c group by c.id")
        assertTrue(myFixture.doHighlighting().none { it.description?.contains("GROUP BY") == true })
    }

    fun testQualifyIntention() {
        sql("select * from ord<caret>ers", schema = "shop")
        myFixture.launchAction(myFixture.findSingleIntention("Qualify with schema"))
        assertEquals("select * from shop.orders", myFixture.editor.document.text)
    }

    fun testFlipJoin() {
        sql("select * from shop.orders o jo<caret>in shop.customers c on c.id = o.customer_id")
        myFixture.launchAction(myFixture.findSingleIntention("Swap joined tables (may change outer join meaning)"))
        assertEquals("select * from shop.customers c join shop.orders o on c.id = o.customer_id", myFixture.editor.document.text)
    }

    fun testJoinCardinalityHints() {
        val f = sql("select * from shop.orders o join shop.customers c on c.id = o.customer_id join shop.order_items i on i.order_id = o.id")
        val hints = PsiTreeUtil.findChildrenOfType(f, SqlJoinClause::class.java).mapNotNull { JoinCardinality.hint(it)?.second }
        assertEquals(listOf("1..n<->1:", "1<->1..n:"), hints)
    }
}
