package dev.teo.mydb.plugin.sql

import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.refactoring.DbRename
import dev.teo.mydb.plugin.sql.psi.DbObjectElement
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Acceptance tests of */
class RefactoringTest : MyDbTestCase() {
    private var fresh: DataSourceConfig? = null

    override fun tearDown() {
        try { fresh?.let { ConsoleService.getInstance(project).close("rename:${it.id}") } } finally { super.tearDown() }
    }

    /** A data source on its own copy of the seed, so DDL does not leak into other tests. */
    private fun freshSql(text: String) {
        val config = freshDataSource("rename-ds")
        fresh = config
        val file = myFixture.configureByText("console.sql", text)
        SqlDataSourceBinding.getInstance(project).bind(file.virtualFile, config.id, null)
    }

    private fun targetAtCaret(): DbObjectElement =
        DbRename.target(myFixture.editor, myFixture.file) ?: error("no database object at caret")

    private fun columnExists(table: String, column: String): Boolean =
        TestDatabase.query(fresh!!, "select count(*) from information_schema.columns where table_schema = 'shop' and table_name = '$table' and column_name = '$column'") { it.getInt(1) == 1 }

    fun `test column rename preview DDL`() {
        sql("select c.em<caret>ail from shop.customers c;")
        val target = targetAtCaret()
        assertEquals("column", target.kind)
        assertEquals("alter table shop.customers\n    rename column email to contact_email;", DbRename.ddl(target, "contact_email"))
    }

    fun `test refactor renames column in database and every usage`() {
        freshSql("select c.em<caret>ail from shop.customers c where c.email like '%@x';")
        DbRename.perform(project, targetAtCaret(), "contact_email", executeDdl = true)
        assertEquals("select c.contact_email from shop.customers c where c.contact_email like '%@x';", myFixture.editor.document.text)
        assertTrue(columnExists("customers", "contact_email"))
        assertFalse(columnExists("customers", "email"))
    }

    fun `test table rename preview and usage count`() {
        sql("select o.id from shop.ord<caret>ers o join shop.order_items i on i.order_id = o.id;")
        val target = targetAtCaret()
        assertEquals("table", target.kind)
        assertEquals("alter table shop.orders\n    rename to orders_v2;", DbRename.ddl(target, "orders_v2"))
        assertEquals(1, DbRename.findUsages(project, target).size)
    }

    fun `test without executing SQL only code changes`() {
        freshSql("select c.em<caret>ail from shop.customers c;")
        DbRename.perform(project, targetAtCaret(), "contact_email", executeDdl = false)
        assertEquals("select c.contact_email from shop.customers c;", myFixture.editor.document.text)
        assertTrue(columnExists("customers", "email"))
    }

    fun `test comments and strings are renamed only when asked`() {
        freshSql("-- email of the customer\nselect c.em<caret>ail, 'email' from shop.customers c where c.email is not null;")
        val target = targetAtCaret()
        val none = DbRename.findTextOccurrences(project, target, dev.teo.mydb.plugin.refactoring.RenameOptions())
        assertEquals(0, none.size)
        val opts = dev.teo.mydb.plugin.refactoring.RenameOptions(commentsAndStrings = true)
        assertEquals(2, DbRename.findTextOccurrences(project, target, opts).size)
        DbRename.perform(project, target, "contact_email", executeDdl = false, options = opts)
        assertEquals("-- contact_email of the customer\nselect c.contact_email, 'contact_email' from shop.customers c where c.contact_email is not null;",
            myFixture.editor.document.text)
    }

    fun `test rename scope limited to open files skips other consoles`() {
        freshSql("select c.em<caret>ail from shop.customers c;")
        val target = targetAtCaret()
        assertEquals(listOf(myFixture.file.virtualFile), DbRename.candidateFiles(project, target, dev.teo.mydb.plugin.refactoring.RenameScope.OPEN_FILES))
    }

    fun `test rename dialog offers search options, scope and open in console`() {
        freshSql("select c.em<caret>ail from shop.customers c;")
        val dialog = dev.teo.mydb.plugin.refactoring.DbRenameDialog(project, targetAtCaret())
        try {
            assertEquals(dev.teo.mydb.plugin.refactoring.RenameOptions(), dialog.options)
            assertEquals(listOf("Cancel", "Open in Console", "Preview", "Refactor"), dialog.buttonTexts())
        } finally { dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test failing DDL leaves code untouched`() {
        freshSql("select c.em<caret>ail from shop.customers c;")
        val before = myFixture.editor.document.text
        val error = runCatching { DbRename.perform(project, targetAtCaret(), "name", executeDdl = true) }.exceptionOrNull()
        assertNotNull("rename onto an existing column must fail", error)
        assertTrue(error!!.message!!, error.message!!.contains("already exists"))
        assertEquals(before, myFixture.editor.document.text)
        assertTrue(columnExists("customers", "email"))
    }

    fun `test alias rename in place updates every occurrence`() {
        sql("select r.id, r.name from shop.customers r<caret> where r.email is not null order by r.id;")
        myFixture.renameElementAtCaret("rv")
        assertEquals("select rv.id, rv.name from shop.customers rv where rv.email is not null order by rv.id;", myFixture.editor.document.text)
    }

    fun `test refactor is disabled while the name is unchanged`() {
        sql("select c.em<caret>ail from shop.customers c;")
        val dialog = dev.teo.mydb.plugin.refactoring.DbRenameDialog(project, targetAtCaret())
        try { assertFalse(dialog.isOKActionEnabled) } finally { dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) }
    }
}
