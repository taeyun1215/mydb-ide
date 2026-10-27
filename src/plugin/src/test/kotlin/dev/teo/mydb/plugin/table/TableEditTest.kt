package dev.teo.mydb.plugin.table

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.console.OutputLog
import dev.teo.mydb.plugin.grid.JdbcGridHookUp

/** Table editor and grid editing against PostgreSQL: */
class TableEditTest : MyDbTestCase() {
    private var editor: TableDataEditor? = null
    private var file: TableDataFile? = null

    override fun tearDown() {
        try {
            editor?.let(Disposer::dispose)
            file?.let { ConsoleService.getInstance(project).close(it.sessionKey) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }

    private fun open(config: DataSourceConfig, table: String): DataGrid {
        val f = TableDataFile(config.id, config.url.substringAfterLast('/'), "shop", table)
        file = f
        val e = TableDataEditor(project, f)
        editor = e
        waitFor("first page of $table") { e.grid != null }
        return e.grid!!
    }

    private val DataGrid.hookUp get() = dataHookup as JdbcGridHookUp
    private val DataGrid.edits get() = hookUp.mutator as JdbcGridHookUp.Mutator
    private fun DataGrid.rows() = getDataModel(DataAccessType.DATABASE_DATA).rowCount
    private fun DataGrid.cell(row: Int, col: Int): Any? {
        val m = getDataModel(DataAccessType.DATABASE_DATA)
        return m.getValueAt(ModelIndex.forRow(m, row), ModelIndex.forColumn(m, col))
    }
    private fun DataGrid.rowOf(id: Int) = (0 until rows()).first { (cell(it, 0) as Number).toInt() == id }

    private fun DataGrid.setCell(row: Int, col: Int, value: Any?) {
        val m = getDataModel(DataAccessType.DATABASE_DATA)
        edits.mutate(GridRequestSource(null), ModelIndexSet.forRows(m, row), ModelIndexSet.forColumns(m, col), value, false)
    }

    private fun DataGrid.submitAndWait(): SubmitResult {
        val before = hookUp.lastSubmit
        edits.submit(GridRequestSource(null), true)
        waitFor("submit") { hookUp.lastSubmit !== before && hookUp.lastSubmit != null }
        return hookUp.lastSubmit!!
    }

    fun `test table editor loads the first page with LIMIT 501`() {
        val grid = open(dataSource, "products")
        assertEquals(20, grid.rows())
        val log = OutputLog.getInstance(project).transcript("products")
        val select = log.first { it.startsWith("obs.shop> ") }
        assertTrue(select, select.contains("FROM shop.products") && select.endsWith("LIMIT 501"))
        assertFalse(grid.hookUp.isReadOnly)
    }

    fun `test filter bar completes column names and filters on reload`() {
        val grid = open(dataSource, "products")
        val e = editor!!
        assertTrue(e.completionVariants(emptyList()).containsAll(listOf("id", "sku", "price")))
        assertTrue(e.where is com.intellij.ui.TextFieldWithAutoCompletion<*>)
        e.where.text = "id <= 3"; e.orderBy.text = "id desc"
        e.load()
        waitFor("filtered") { e.grid !== grid && e.grid!!.getDataModel(DataAccessType.DATABASE_DATA).rowCount == 3 }
        assertEquals(3, (e.grid!!.getDataModel(DataAccessType.DATABASE_DATA).let { m -> m.getValueAt(ModelIndex.forRow(m, 0), ModelIndex.forColumn(m, 0)) } as Number).toInt())
    }

    fun `test filter reload shows a spinner until the page arrives`() {
        val grid = open(dataSource, "products")
        val e = editor!!
        e.where.text = "id <= 3 and (select true from pg_sleep(1))"
        e.load()
        assertTrue("loading while the query runs", e.isLoading)
        waitFor("spinner visible") { e.spinnerVisible }
        waitFor("filtered") { e.grid !== grid }
        assertFalse(e.isLoading)
        assertFalse(e.spinnerVisible)
        assertNull(e.error)
        assertEquals(3, e.grid!!.getDataModel(DataAccessType.DATABASE_DATA).rowCount)
    }

    fun `test failed filter shows the error and keeps the previous grid`() {
        val grid = open(dataSource, "products")
        val e = editor!!
        e.where.text = "no_such_column = 1"
        e.load()
        waitFor("error shown") { e.error != null }
        assertFalse(e.isLoading)
        assertTrue(e.error!!, e.error!!.contains("no_such_column"))
        assertSame(grid, e.grid)
        e.where.text = "id <= 3"
        e.load()
        waitFor("recovered") { e.grid !== grid }
        assertNull("error cleared by the next load", e.error)
    }

    fun `test a newer filter wins over a slower earlier one`() {
        val grid = open(dataSource, "products")
        val e = editor!!
        e.where.text = "id <= 5 and (select true from pg_sleep(1))"
        e.load()
        e.where.text = "id <= 2"
        e.load()
        waitFor("newer filter applied") { e.grid !== grid && !e.isLoading }
        // Let the slow first query finish; its result must be dropped.
        Thread.sleep(1500)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(2, e.grid!!.getDataModel(DataAccessType.DATABASE_DATA).rowCount)
    }

    fun `test edits stay local until submit, then commit and reload`() {
        val config = freshDataSource("edit-ds")
        val grid = open(config, "products")
        val row = grid.rowOf(3)
        grid.setCell(row, 2, "renamed 3")
        assertTrue(grid.edits.hasPendingChanges())
        assertEquals("product 3", TestDatabase.query(config, "select title from shop.products where id = 3") { it.getString(1) })

        assertEquals(SubmitResult.Ok(1), grid.submitAndWait())
        assertTrue(OutputLog.getInstance(project).transcript("products").any { it.startsWith("Submitted 1 change in ") })
        assertEquals("renamed 3", TestDatabase.query(config, "select title from shop.products where id = 3") { it.getString(1) })
        assertFalse(grid.edits.hasPendingChanges())
        waitFor("grid reloaded from the database") { grid.rows() == 20 && grid.cell(grid.rowOf(3), 2) == "renamed 3" }
    }

    private fun gridEvent(grid: DataGrid, id: String) = com.intellij.testFramework.TestActionEvent.createTestEvent(
        com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id),
        com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(com.intellij.database.DatabaseDataKeys.DATA_GRID_KEY, grid).build())

    fun `test grid toolbar has the session transaction and DDL buttons`() {
        val config = freshDataSource("grid-tx-ds")
        val grid = open(config, "products")
        val wrapper = dev.teo.mydb.plugin.grid.GridToolbar.component(grid)
        val toolbar = com.intellij.util.ui.UIUtil.findComponentsOfType(wrapper, com.intellij.openapi.actionSystem.impl.ActionToolbarImpl::class.java)
            .single { it.place == "MyDbGridToolbar" }
        val ids = toolbar.actionGroup.getChildren(null).mapNotNull { com.intellij.openapi.actionSystem.ActionManager.getInstance().getId(it) }
        assertTrue(ids.toString(), ids.containsAll(listOf("MyDb.TxMode", "MyDb.Commit", "MyDb.Rollback", "MyDb.GridDdl")))

        // switching the grid's Tx to manual changes the table editor session, not a console
        val manual = dev.teo.mydb.plugin.console.TxModeComboAction.popupGroup().getChildren(null)
            .filterIsInstance<com.intellij.openapi.actionSystem.ToggleAction>().single { it.templateText == "Manual" }
        val ev = gridEvent(grid, "MyDb.TxMode")
        manual.setSelected(com.intellij.testFramework.TestActionEvent.createTestEvent(manual, ev.dataContext), true)
        waitFor("table editor session in manual mode") { grid.hookUp.session.txMode == dev.teo.mydb.core.datasource.TxMode.MANUAL }
        grid.setCell(grid.rowOf(4), 2, "in tx")
        grid.submitAndWait()
        assertTrue(grid.hookUp.session.transactionOpen)
        val commit = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("MyDb.Commit")
        val ce = gridEvent(grid, "MyDb.Commit"); commit.update(ce)
        assertTrue("commit visible and enabled for the grid session", ce.presentation.isVisible && ce.presentation.isEnabled)
        assertEquals("product 4", TestDatabase.query(config, "select title from shop.products where id = 4") { it.getString(1) })
        commit.actionPerformed(ce)
        waitFor("commit") { !grid.hookUp.session.transactionOpen }
        assertEquals("in tx", TestDatabase.query(config, "select title from shop.products where id = 4") { it.getString(1) })

        val ddl = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("MyDb.GridDdl")
        val de = gridEvent(grid, "MyDb.GridDdl"); ddl.update(de)
        assertTrue("DDL enabled", de.presentation.isEnabled)
        ddl.actionPerformed(de)
        val opened = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.single()
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(opened)!!.text
        assertTrue(text, text.contains("create table products") || text.contains("CREATE TABLE"))
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(opened)
    }

    fun `test data compare of two grids reports changed rows`() {
        val config = freshDataSource("compare-data-ds", "create table shop.products_copy as select * from shop.products; update shop.products_copy set title = 'other' where id = 5; delete from shop.products_copy where id = 6;")
        val grid = open(config, "products")
        val left = dev.teo.mydb.plugin.grid.DataCompare.data(grid, "products")!!
        val right = dev.teo.mydb.plugin.transfer.DataExport.loadTable(project, config, "shop", "products_copy")
        val req = dev.teo.mydb.plugin.grid.DataCompare.request("products", left, "products_copy", right, dev.teo.mydb.plugin.grid.DataCompare.key(grid))
        assertTrue(req.title, req.title!!.contains("1 changed, 1 only on the left, 0 only on the right, 18 identical"))
        assertEquals(listOf("id"), dev.teo.mydb.plugin.grid.DataCompare.key(grid))
    }

    fun `test failed submit rolls back and keeps pending changes`() {
        val config = freshDataSource("edit-fk-ds")
        val grid = open(config, "products")
        // product 1 is referenced by order_items, so deleting it violates the foreign key
        val m = grid.getDataModel(DataAccessType.DATABASE_DATA)
        grid.edits.deleteRows(GridRequestSource(null), ModelIndexSet.forRows(m, grid.rowOf(1)))
        grid.setCell(grid.rowOf(2), 2, "should not be saved")

        val result = grid.submitAndWait()
        assertTrue(result.toString(), result is SubmitResult.Failed && result.sqlState == "23503")
        // only the row whose statement failed is red; the other pending row keeps its colour
        val failedRow = grid.rowOf(1)
        assertEquals(failedRow, grid.edits.failedRow)
        assertFalse(grid.edits.isFailed)
        val m2 = grid.getDataModel(DataAccessType.DATABASE_DATA)
        val red = com.intellij.openapi.ui.MessageType.ERROR.popupBackground
        assertEquals(red, grid.colorModel.getCellBackground(ModelIndex.forRow(m2, failedRow), ModelIndex.forColumn(m2, 1)))
        assertFalse(red == grid.colorModel.getCellBackground(ModelIndex.forRow(m2, grid.rowOf(2)), ModelIndex.forColumn(m2, 2)))
        // the output tab records the statement that was sent and the error
        val log = OutputLog.getInstance(project).transcript("products")
        assertTrue(log.toString(), log.any { it.startsWith("DELETE FROM shop.products WHERE id = 1") })
        assertTrue(log.toString(), log.any { it.startsWith("[23503] ") })
        assertFalse(log.toString(), log.any { it.startsWith("UPDATE shop.products") })
        assertTrue(grid.edits.hasPendingChanges())
        assertEquals("product 2", TestDatabase.query(config, "select title from shop.products where id = 2") { it.getString(1) })
        assertEquals(1, TestDatabase.query(config, "select count(*) from shop.products where id = 1") { it.getInt(1) })
    }
}
