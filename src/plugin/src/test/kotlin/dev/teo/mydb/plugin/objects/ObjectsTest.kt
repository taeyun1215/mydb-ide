package dev.teo.mydb.plugin.objects

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import dev.teo.mydb.core.ddl.ConstraintPlacement
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.generator.SqlGeneratorPanel
import dev.teo.mydb.plugin.model.ModelService

/** Create/modify execution and the SQL generator: */
class ObjectsTest : MyDbTestCase() {
    private var ddlKey: String? = null

    override fun tearDown() {
        try { ddlKey?.let { ConsoleService.getInstance(project).close(it) } } finally { super.tearDown() }
    }

    private fun reviews(schema: dev.teo.mydb.core.model.DbSchema) = TableDraft(schema, null).apply {
        name = "reviews"
        addColumn().apply { name = "id"; identity = true }
        addColumn().apply { name = "product_id"; notNull = true }
        addColumn().apply { name = "body"; type = "text" }
        addPrimaryKey("id")
        addForeignKey().apply { refTable = "products"; pairs += "product_id" to "id" }
        refreshAutoNames()
    }

    fun `test creating a table runs one create table and shows it in the model`() {
        val config = freshDataSource("objects-ds")
        ddlKey = ObjectDdl.sessionKey(config)
        val db = config.url.substringAfterLast('/')
        val schema = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!
        val draft = reviews(schema)
        assertEquals(1, draft.statements().size)
        assertNull(ObjectDdl.execute(project, config, db, draft, draft.statements()))

        val table = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!.table("reviews")
        assertNotNull(table)
        assertEquals("reviews_pk", table!!.primaryKey?.name)
        assertEquals(listOf("reviews_products_id_fk"), table.foreignKeys.map { it.name })

        // modify: rename the table; automatic constraint names follow
        val edit = TableDraft(ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!, table)
        edit.name = "reviews_2"; edit.refreshAutoNames()
        assertNull(ObjectDdl.execute(project, config, db, edit, edit.statements()))
        val after = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!
        assertNull(after.table("reviews"))
        assertEquals("reviews_2_pk", after.table("reviews_2")?.primaryKey?.name)
    }

    fun `test failing DDL reports the server error`() {
        val config = freshDataSource("objects-fail-ds")
        ddlKey = ObjectDdl.sessionKey(config)
        val db = config.url.substringAfterLast('/')
        val schema = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!
        val draft = TableDraft(schema, null).apply { name = "products"; addColumn().name = "x" }
        val error = ObjectDdl.execute(project, config, db, draft, draft.statements())
        assertTrue(error, error!!.startsWith("[42P07]"))
    }

    fun `test create and modify dialogs open on the draft`() {
        val schema = TestDatabase.model.schema("obs", "shop")!!
        for (draft in listOf(reviews(schema), TableDraft(schema, schema.table("orders")))) {
            val dialog = TableDialog(project, dataSource, "obs", draft)
            try { assertSame(draft, dialog.draft) } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        }
    }

    fun `test plus on a folder adds that kind directly`() {
        val schema = TestDatabase.model.schema("obs", "shop")!!
        val draft = TableDraft(schema, null)
        val dialog = TableDialog(project, dataSource, "obs", draft)
        try {
            dialog.selectFolder("Columns")
            assertTrue(dialog.quickAdd() is ColumnDraft)
            assertEquals(1, draft.columns.size)
            dialog.selectFolder("Foreign Keys")
            assertTrue(dialog.quickAdd() is FkDraft)
            dialog.selectFolder("Keys")
            assertNull("no primary key yet: the popup asks", dialog.quickAdd())
            assertEquals(listOf("Primary Key", "Unique Key"), dialog.popupChoices())
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test indexes and checks folders add their kind and keep automatic names`() {
        val schema = TestDatabase.model.schema("obs", "shop")!!
        val draft = TableDraft(schema, null).apply { name = "notes"; addColumn().apply { name = "body"; type = "text" } }
        val dialog = TableDialog(project, dataSource, "obs", draft)
        try {
            dialog.selectFolder("Indexes")
            val idx = dialog.quickAdd() as IndexDraft
            idx.columns += "body"; draft.refreshAutoNames()
            assertEquals("notes_body_index", idx.name)
            dialog.selectFolder("Checks")
            val ch = dialog.quickAdd() as CheckDraft
            ch.expression = "length(body) > 0"
            assertEquals("notes_check", ch.name)
            draft.addColumn().apply { name = "len"; generated = "length(body)" }
            assertTrue(dialog.popupChoices().containsAll(listOf("Index", "Check")))
            val ddl = draft.preview()
            assertTrue(ddl, ddl.contains("len  integer generated always as (length(body)) stored"))
            assertTrue(ddl, ddl.contains("constraint notes_check\n        check (length(body) > 0)"))
            assertTrue(ddl, ddl.contains("create index notes_body_index\n    on shop.notes (body);"))
            draft.name = "memo"; draft.refreshAutoNames()
            assertEquals("memo_body_index", idx.name)
            assertEquals("memo_check", ch.name)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test modify dialog adds and drops checks and indexes on the server`() {
        val config = freshDataSource("objects-idx-ds")
        ddlKey = ObjectDdl.sessionKey(config)
        val db = config.url.substringAfterLast('/')
        fun products() = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!.let { it to it.table("products")!! }
        val (schema, products) = products()
        val edit = TableDraft(schema, products)
        val existingChecks = edit.checks.size
        edit.addIndex("title")
        edit.addCheck("length(title) > 0").also { edit.renameCheck(it, "products_title_check") }
        assertEquals(edit.statements().joinToString("\n"), 2, edit.statements().size)
        assertNull(ObjectDdl.execute(project, config, db, edit, edit.statements()))
        val (schema2, stored) = products()
        assertEquals(listOf("products_title_index"), stored.indexes.filter { !it.backsConstraint && it.name.endsWith("_index") }.map { it.name })
        assertEquals(existingChecks + 1, stored.checks.size)

        // unchanged checks produce no statements; dropping the new one does
        val again = TableDraft(schema2, stored)
        assertEquals(emptyList<String>(), again.statements())
        again.checks.removeIf { it.name == "products_title_check" }
        again.indexes.removeIf { it.name == "products_title_index" }
        assertEquals(listOf("alter table shop.products\n    drop constraint products_title_check;", "drop index shop.products_title_index;"), again.statements())
        assertNull(ObjectDdl.execute(project, config, db, again, again.statements()))
        assertEquals(existingChecks, products().second.checks.size)
    }

    fun `test new popup offers view, sequence and schema and their dialogs run the script`() {
        assertEquals(listOf("Table", "View", "Materialized View", "Sequence", "Schema"), newKinds())
        val config = freshDataSource("objects-new-ds")
        ddlKey = ObjectDdl.sessionKey(config)
        val db = config.url.substringAfterLast('/')
        val schema = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!
        val view = dialog(project, config, db, schema, "View") as ViewDialog
        try {
            view.draft.name = "cheap"; view.draft.query = "select id, title from shop.products where price < 5"
            assertEquals("create view shop.cheap as\nselect id, title from shop.products where price < 5;", view.previewText())
            assertNull(ObjectDdl.execute(project, config, db, "shop", view.statements(), view.refreshTarget()))
        } finally { view.close(DialogWrapper.CANCEL_EXIT_CODE) }
        assertTrue(ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!.table("cheap")!!.isView)

        val seq = dialog(project, config, db, schema, "Sequence") as SequenceDialog
        try {
            seq.draft.name = "ticket_seq"; seq.draft.start = 100
            assertNull(ObjectDdl.execute(project, config, db, "shop", seq.statements(), seq.refreshTarget()))
        } finally { seq.close(DialogWrapper.CANCEL_EXIT_CODE) }
        assertTrue(ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!.sequences.any { it.name == "ticket_seq" })

        val sch = dialog(project, config, db, schema, "Schema") as SchemaDialog
        try {
            sch.draft.name = "sales"
            assertNull(ObjectDdl.execute(project, config, db, null, sch.statements(), sch.refreshTarget()))
        } finally { sch.close(DialogWrapper.CANCEL_EXIT_CODE) }
        assertTrue("sales" in ModelService.getInstance(project).model(config.id)!!.database(db)!!.allSchemaNames)
    }

    fun `test generator options apply to the selected table`() {
        val schema = TestDatabase.model.schema("obs", "shop")!!
        val panel = SqlGeneratorPanel(project)
        try {
            panel.show(ExplorerNode.Table(dataSource, "obs", "shop", schema.table("order_items")!!))
            assertTrue(panel.text(), panel.text().startsWith("create table order_items"))
            panel.setIfNotExists(true)
            assertTrue(panel.text(), panel.text().startsWith("create table if not exists order_items"))
            panel.setPlacement(ConstraintPlacement.TABLE)
            assertTrue(panel.text(), Regex("""foreign key \(order_id\)""").containsMatchIn(panel.text()))
        } finally { Disposer.dispose(panel) }
    }

    fun `test generator modes write drops in reverse dependency order`() {
        val schema = TestDatabase.model.schema("obs", "shop")!!
        val panel = SqlGeneratorPanel(project)
        try {
            panel.show(ExplorerNode.Schema(dataSource, "obs", schema))
            panel.setMode(dev.teo.mydb.core.ddl.GenerationMode.DROP)
            val lines = panel.text().lines()
            assertEquals("drop view if exists order_totals;", lines.first())
            // children before parents
            assertTrue(lines.toString(), lines.indexOf("drop table if exists order_items;") < lines.indexOf("drop table if exists orders;"))
            assertTrue(lines.toString(), lines.indexOf("drop table if exists orders;") < lines.indexOf("drop table if exists customers;"))
            panel.setMode(dev.teo.mydb.core.ddl.GenerationMode.DROP_AND_CREATE)
            assertTrue(panel.text().contains("drop table if exists customers;") && panel.text().contains("create table customers"))
        } finally { Disposer.dispose(panel) }
    }
}
