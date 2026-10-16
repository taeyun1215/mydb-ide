package dev.teo.mydb.plugin.explorer

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.util.Disposer
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.util.ui.tree.TreeUtil
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.model.ModelService
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode

/** Database explorer:–4. */
class ExplorerTest : MyDbTestCase() {
    private lateinit var panel: DatabaseExplorerPanel

    override fun setUp() {
        super.setUp()
        panel = DatabaseExplorerPanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.refreshTree()
    }

    private fun labels(): List<String> = TreeUtil.treeNodeTraverser(panel.tree.model.root as DefaultMutableTreeNode).toList()
        .mapNotNull { ((it as DefaultMutableTreeNode).userObject as? ExplorerNode)?.label }

    fun `test speed search matches tables and columns by substring`() {
        val search = SpeedSearchSupply.getSupply(panel.tree, true) as com.intellij.ui.SpeedSearchBase<*>
        val matched = labels().filter { search.comparator.matchingFragments("ord", it) != null }.toSet()
        assertTrue(matched.toString(), matched.containsAll(setOf("order_items", "orders", "ordered_at")))
        assertFalse(matched.contains("customers"))
    }

    fun `test filter shows only matching tables and columns`() {
        panel.setFilter("ord")
        val shown = labels()
        assertTrue(shown.toString(), shown.containsAll(listOf("orders", "order_items", "ordered_at")))
        assertFalse(shown.toString(), "products" in shown)
        assertFalse("customers has no matching column", "customers" in shown)
        // orders matched by name keeps all its columns; order_totals (a view) matched by name too
        assertTrue(shown.toString(), "customer_id" in shown)
        panel.showFilter(false)
        assertTrue("products" in labels())
    }

    fun `test schema badge choices and applying a new scope`() {
        val config = freshDataSource("scope-ds", "create schema extra; create table extra.t (x int);")
        val db = config.url.substringAfterLast('/')
        val database = ModelService.getInstance(project).model(config.id)!!.database(db)!!
        val choices = ChooseSchemasAction.schemaChoices(config, database).toMap()
        assertEquals(true, choices["shop"])
        assertEquals(false, choices["extra"])
        assertFalse(choices.keys.any { it.startsWith("pg_") })
        ChooseSchemasAction.applySchemas(project, config, db, listOf("shop", "extra"))
        waitFor("extra introspected") { ModelService.getInstance(project).model(config.id)?.schema(db, "extra")?.table("t") != null }
        val stored = dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).find(config.id)!!
        assertEquals(setOf("shop", "extra"), stored.introspectionScope.schemas.filter { it.database == db }.map { it.schema }.toSet())
    }

    fun `test go to symbol finds tables and columns`() {
        val c = DbSymbolContributor()
        val names = mutableListOf<String>()
        c.processNames({ names += it; true }, com.intellij.psi.search.GlobalSearchScope.allScope(project), null)
        assertTrue(names.containsAll(listOf("orders", "customer_id", "order_totals")))
        val found = mutableListOf<com.intellij.navigation.NavigationItem>()
        c.processElementsWithName("orders", { found += it; true }, com.intellij.util.indexing.FindSymbolParameters.wrap("orders", project, false))
        val orders = found.single() as dev.teo.mydb.plugin.sql.psi.DbObjectElement
        assertEquals("table", orders.kind)
        assertEquals("obs.shop", orders.locationString)
        // the platform's Go to Symbol model lists it as well
        val model = com.intellij.ide.util.gotoByName.GotoSymbolModel2(project, testRootDisposable)
        assertTrue(model.getNames(false).contains("customer_id"))
    }

    fun `test copy reference and data source colour`() {
        val shop = TestDatabase.model.schema("obs", "shop")!!
        val orders = ExplorerNode.Table(dataSource, "obs", "shop", shop.table("orders")!!)
        assertEquals("shop.orders", CopyReferenceAction.qualifiedName(orders))
        assertEquals("shop.orders.status", CopyReferenceAction.qualifiedName(ExplorerNode.Column(dataSource, "obs", "shop", orders.table, orders.table.column("status")!!)))

        val config = freshDataSource("color-ds")
        DataSourceColorAction.setColor(project, config, "#5FB865")
        val stored = dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).find(config.id)!!
        assertEquals("#5FB865", stored.color)
        val f = myFixture.configureByText("colored.sql", "select 1;").virtualFile
        dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project).bind(f, config.id)
        assertNotNull(DataSourceTabColorProvider().getEditorTabColor(project, f))
        DataSourceColorAction.setColor(project, stored, null)
        assertNull(DataSourceTabColorProvider().getEditorTabColor(project, f))
    }

    fun `test quick documentation uses the platform documentation layout`() {
        val orders = ExplorerNode.Table(dataSource, "obs", "shop", TestDatabase.model.schema("obs", "shop")!!.table("orders")!!)
        val element = orders.asElement(project)!!
        assertEquals(dev.teo.mydb.plugin.sql.SqlLanguage, element.language)
        val html = com.intellij.lang.LanguageDocumentation.INSTANCE.forLanguage(element.language).generateDoc(element, null)!!
        assertTrue(html, html.contains(com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_START) && html.contains("obs.shop.orders"))
    }

    fun `test table context menu has the entry points and shortcuts`() {
        val am = ActionManager.getInstance()
        val popup = am.getAction("MyDb.Explorer.Popup") as ActionGroup
        val top = popup.getChildren(null).filter { it !is Separator }.map { am.getId(it) }
        for (id in listOf("MyDb.NewObject", "MyDb.ModifyTable", "MyDb.EditData", "MyDb.Refresh", "MyDb.Rename", "MyDb.QuickDoc",
                "MyDb.Compare", "MyDb.Explorer.Navigation", "MyDb.FindUsages", "MyDb.Explorer.SqlScripts", "MyDb.Explorer.ObjectActions",
                "MyDb.Explorer.ImportExport", "MyDb.Diagrams", "MyDb.Drop")) {
            assertTrue("$id in $top", id in top)
        }
        // shortcuts are keymap entries, so menus show them (default keymap in tests; macOS uses ⌘ instead of Ctrl)
        val keymap = com.intellij.openapi.keymap.KeymapManager.getInstance().activeKeymap
        val expected = mapOf("MyDb.NewObject" to "control N", "MyDb.ModifyTable" to "control F6", "MyDb.Refresh" to "control R",
            "MyDb.Rename" to "shift F6", "MyDb.QuickDoc" to "control Q", "MyDb.Compare" to "control D", "MyDb.GoToDdl" to "control B",
            "MyDb.JumpToConsole" to "shift control F10", "MyDb.FindUsages" to "alt F7", "MyDb.Drop" to "DELETE")
        val mac = com.intellij.openapi.util.SystemInfo.isMac
        for ((id, defaultStroke) in expected) {
            val stroke = if (!mac) defaultStroke else if (id == "MyDb.QuickDoc") "F1" else defaultStroke.replace("control", "meta")
            val strokes = keymap.getShortcuts(id).filterIsInstance<KeyboardShortcut>().map { it.firstKeyStroke }
            assertTrue("$id has $stroke: $strokes", KeyStroke.getKeyStroke(stroke) in strokes)
        }
        // outside the explorer (no selection) these actions stay disabled, so editor bindings of the same keys still work
        val e = com.intellij.testFramework.TestActionEvent.createTestEvent(am.getAction("MyDb.Drop"))
        am.getAction("MyDb.Drop").update(e)
        assertFalse(e.presentation.isEnabled)
    }

    fun `test statement templates for a table`() {
        val customers = TestDatabase.model.schema("obs", "shop")!!.table("customers")!!
        assertEquals("SELECT id, name, email, created_at\nFROM shop.customers;", SqlTemplates.statement(SqlTemplates.Kind.SELECT, "shop", customers))
        assertEquals("INSERT INTO shop.customers (name, email, created_at)\nVALUES ('', '', null);", SqlTemplates.statement(SqlTemplates.Kind.INSERT, "shop", customers))
        assertEquals("DELETE\nFROM shop.customers\nWHERE id = 0;", SqlTemplates.statement(SqlTemplates.Kind.DELETE, "shop", customers))
    }

    fun `test quick documentation shows columns and DDL`() {
        val orders = ExplorerNode.Table(dataSource, "obs", "shop", TestDatabase.model.schema("obs", "shop")!!.table("orders")!!)
        val html = DbDocs.html(project, orders.asElement(project)!!)
        assertTrue(html, html.contains("customer_id") && html.contains("create table orders"))
    }

    fun `test truncate and delete run after confirmation and refresh the model`() {
        val config = freshDataSource("drop-ds")
        val db = config.url.substringAfterLast('/')
        fun node(t: String) = ExplorerNode.Table(config, db, "shop", ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!.table(t)!!)
        assertNull(ObjectStatements.run(project, node("order_items"), ObjectStatements.truncate("shop", node("order_items").table)))
        assertEquals(0, TestDatabase.query(config, "select count(*) from shop.order_items") { it.getInt(1) })
        // the view order_totals depends on order_items: the server refuses and the error is reported
        val blocked = ObjectStatements.run(project, node("order_items"), ObjectStatements.drop("shop", node("order_items").table))
        assertTrue(blocked, blocked!!.startsWith("[2BP01]"))
        assertEquals("drop view shop.order_totals;", ObjectStatements.drop("shop", node("order_totals").table))
        assertNull(ObjectStatements.run(project, node("order_totals"), ObjectStatements.drop("shop", node("order_totals").table)))
        assertNull(ObjectStatements.run(project, node("order_items"), ObjectStatements.drop("shop", node("order_items").table)))
        val schema = ModelService.getInstance(project).model(config.id)!!.schema(db, "shop")!!
        assertNull(schema.table("order_items"))
        assertNull(schema.table("order_totals"))
        dev.teo.mydb.plugin.console.ConsoleService.getInstance(project).close(dev.teo.mydb.plugin.objects.ObjectDdl.sessionKey(config))
    }

    fun `test refresh keeps expanded nodes and the selection`() {
        val tree = panel.tree
        val columns = TreeUtil.treeNodeTraverser(tree.model.root as DefaultMutableTreeNode).toList().map { it as DefaultMutableTreeNode }
            .first { n -> (n.userObject as? ExplorerNode.Folder)?.let { it.kind == FolderKind.COLUMNS && it.table == "orders" } == true }
        val path = javax.swing.tree.TreePath(columns.path)
        tree.expandPath(path); tree.selectionPath = path
        // a new model object (as after introspection) rebuilds every node
        ModelService.getInstance(project).put(TestDatabase.model.copy())
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        panel.refreshTree()
        val selected = tree.selectionPath!!.lastPathComponent as DefaultMutableTreeNode
        assertEquals("columns", (selected.userObject as ExplorerNode).label)
        assertTrue(tree.isExpanded(tree.selectionPath))
    }
}
