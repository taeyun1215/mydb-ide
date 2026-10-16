package dev.teo.mydb.plugin.console

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.IsolationLevel
import dev.teo.mydb.core.datasource.TxMode
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Console transactions and sessions against PostgreSQL: */
class ConsoleTxTest : MyDbTestCase() {
    private lateinit var config: DataSourceConfig
    private val file get() = myFixture.file.virtualFile
    private val consoles get() = ConsoleService.getInstance(project)

    override fun setUp() {
        super.setUp()
        config = freshDataSource("tx-ds")
        val f = myFixture.configureByText("tx_console.sql", "")
        SqlDataSourceBinding.getInstance(project).bind(f.virtualFile, config.id, "shop")
    }

    override fun tearDown() {
        try { consoles.forget(file.url); PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() } finally { super.tearDown() }
    }

    private fun event(action: AnAction): AnActionEvent = TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).add(CommonDataKeys.EDITOR, myFixture.editor).build())

    private fun action(id: String) = ActionManager.getInstance().getAction(id)
    private fun presentation(id: String) = event(action(id)).also { action(id).update(it) }.presentation

    private fun popup() = TxModeComboAction.popupGroup().getChildren(null)

    private fun choose(text: String) {
        val toggle = popup().filterIsInstance<ToggleAction>().single { it.templateText == text }
        toggle.setSelected(event(toggle), true)
        waitFor("tx settings applied") { popup().filterIsInstance<ToggleAction>().single { it.templateText == text }.let { t -> t.isSelected(event(t)) } }
        // the change is applied on a pooled thread; wait until a live session follows it
        consoles.existing(file.url)?.let { h -> waitFor("session follows settings") { h.session.txMode == consoles.txSettings(file.url, config).mode } }
    }

    private fun run(sql: String) = ApplicationManager.getApplication().executeOnPooledThread<ConsoleRunner.RunOutcome?> {
        ConsoleRunner.runStatements(project, file, SqlDataSourceBinding.getInstance(project).context(file)!!, StatementSplitter.split(sql))
    }.get()!!.also { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }

    private fun committedTitle(id: Int) = TestDatabase.query(config, "select title from shop.products where id = $id") { it.getString(1) }

    fun `test dropdown lists two modes and four isolation levels with current values checked`() {
        val items = popup()
        val toggles = items.filterIsInstance<ToggleAction>()
        assertEquals(listOf("Auto", "Manual", "Database Default", "Read Committed", "Repeatable Read", "Serializable"), toggles.map { it.templateText })
        assertEquals(listOf("Auto", "Database Default"), toggles.filter { it.isSelected(event(it)) }.map { it.templateText })
        assertEquals(listOf("Transaction Mode", "Transaction Isolation"), items.filterIsInstance<Separator>().mapNotNull { it.text })
        assertEquals("Tx: Auto", presentation("MyDb.TxMode").text)
    }

    fun `test switching to manual shows disabled commit and rollback`() {
        assertFalse(presentation("MyDb.Commit").isVisible)
        choose("Manual")
        assertEquals("Tx: Manual", presentation("MyDb.TxMode").text)
        for (id in listOf("MyDb.Commit", "MyDb.Rollback")) {
            assertTrue(id, presentation(id).isVisible)
            assertFalse(id, presentation(id).isEnabled)
        }
    }

    fun `test manual transaction is invisible to others until commit`() {
        choose("Manual")
        run("update shop.products set title = 'tx 1' where id = 1;")
        assertTrue(consoles.existing(file.url)!!.session.transactionOpen)
        assertTrue(presentation("MyDb.Commit").isEnabled)
        assertEquals("product 1", committedTitle(1))

        action("MyDb.Rollback").actionPerformed(event(action("MyDb.Rollback")))
        waitFor("rollback") { !consoles.existing(file.url)!!.session.transactionOpen }
        assertEquals("product 1", committedTitle(1))
        assertFalse(presentation("MyDb.Commit").isEnabled)

        run("update shop.products set title = 'tx 2' where id = 1;")
        action("MyDb.Commit").actionPerformed(event(action("MyDb.Commit")))
        waitFor("commit") { !consoles.existing(file.url)!!.session.transactionOpen }
        assertEquals("tx 2", committedTitle(1))
    }

    fun `test clicking the toolbar commit button commits`() {
        choose("Manual")
        run("update shop.products set title = 'tx click' where id = 2;")
        val fileEditor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor(file)
            ?: com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val panel = ConsoleToolbarProvider().collectNotificationData(project, file)!!.apply(fileEditor)!!
        val toolbar = com.intellij.util.ui.UIUtil.findComponentOfType(panel, com.intellij.openapi.actionSystem.impl.ActionToolbarImpl::class.java)!!
        PlatformTestUtil.waitForFuture(toolbar.updateActionsAsync())
        val button = com.intellij.util.ui.UIUtil.findComponentsOfType(toolbar.component, com.intellij.openapi.actionSystem.impl.ActionButton::class.java)
            .single { it.action === action("MyDb.Commit") }
        assertTrue("commit button enabled", button.isEnabled)
        button.click()
        waitFor("commit by click") { !consoles.existing(file.url)!!.session.transactionOpen }
        assertEquals("tx click", committedTitle(2))
    }

    fun `test services tool window rolls back the selected session`() {
        choose("Manual")
        run("update shop.products set title = 'tx services' where id = 3;")
        val panel = ResultsPanel(project)
        try {
            panel.select(file.url)
            assertSame(consoles.existing(file.url), panel.selectedSession())
            val toolbar = com.intellij.util.ui.UIUtil.findComponentsOfType(panel, com.intellij.openapi.actionSystem.impl.ActionToolbarImpl::class.java)
                .single { it.place == ResultsPanel.TOOLBAR_PLACE }
            assertFalse("vertical", toolbar.orientation == javax.swing.SwingConstants.HORIZONTAL)
            PlatformTestUtil.waitForFuture(toolbar.updateActionsAsync())
            val buttons = com.intellij.util.ui.UIUtil.findComponentsOfType(toolbar.component, com.intellij.openapi.actionSystem.impl.ActionButton::class.java)
            assertEquals(listOf(action("MyDb.Commit"), action("MyDb.Rollback")), buttons.map { it.action })
            val rollback = buttons.single { it.action === action("MyDb.Rollback") }
            assertTrue("rollback enabled", rollback.isEnabled)
            rollback.click()
            waitFor("rollback by click") { !consoles.existing(file.url)!!.session.transactionOpen }
            assertEquals("product 3", committedTitle(3))
            PlatformTestUtil.waitForFuture(toolbar.updateActionsAsync())
            assertFalse("disabled without a transaction", rollback.isEnabled)
        } finally { com.intellij.openapi.util.Disposer.dispose(panel) }
    }

    fun `test serializable applies to the next transaction`() {
        choose("Serializable")
        val outcome = run("show transaction isolation level;")
        assertEquals("serializable", outcome.results.single().page.rows.single()[0])
        assertEquals(IsolationLevel.SERIALIZABLE, consoles.txSettings(file.url, config).isolation)
        assertEquals(TxMode.AUTO, consoles.txSettings(file.url, config).mode)
    }

    fun `test session reports the product as application_name`() {
        val outcome = run("select current_setting('application_name');")
        assertEquals("MyDB IDE 0.1.0", outcome.results.single().page.rows.single()[0])
    }

    fun `test console and table editor use separate server sessions`() {
        run("select 1;")
        val table = dev.teo.mydb.plugin.table.TableDataFile(config.id, config.url.substringAfterLast('/'), "shop", "products")
        val editor = dev.teo.mydb.plugin.table.TableDataEditor(project, table)
        try {
            waitFor("table editor loaded") { editor.grid != null }
            val handles = listOfNotNull(consoles.existing(file.url), consoles.existing(table.sessionKey))
            assertEquals(2, handles.size)
            assertNotSame(handles[0].session.connection, handles[1].session.connection)
            val db = config.url.substringAfterLast('/')
            assertEquals(2, TestDatabase.query(config, "select count(*) from pg_stat_activity where datname = '$db' and application_name = 'MyDB IDE 0.1.0'") { it.getInt(1) })
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
            consoles.close(table.sessionKey)
        }
    }
}
