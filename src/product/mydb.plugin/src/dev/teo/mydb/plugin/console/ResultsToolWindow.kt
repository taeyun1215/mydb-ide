package dev.teo.mydb.plugin.console

import com.intellij.database.datagrid.DataGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.plugin.grid.EditTarget
import dev.teo.mydb.plugin.grid.PageQuery
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Bottom tool window with running sessions: tree of data sources and their sessions on
 * the left, the selected session's output and result tabs on the right.
 */
class ResultsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ResultsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "MyDB Services"
        fun panel(project: Project): ResultsPanel? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return null
            return tw.contentManager.contents.firstOrNull()?.component as? ResultsPanel
        }
        fun show(project: Project, then: (ResultsPanel) -> Unit) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            tw.show {
                panel(project)?.let(then)
                // the first show lays the tool window out after our content changes: repaint once it is in place
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    tw.component.revalidate(); tw.component.repaint()
                }
            }
        }
    }
}

class ResultsPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable, com.intellij.openapi.actionSystem.UiDataProvider {
    private val root = DefaultMutableTreeNode("Database")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val right = JPanel(BorderLayout())
    private val sessionViews = HashMap<String, SessionView>()
    private var shownKey: String? = null

    /** Output + result tabs of one session. */
    inner class SessionView(val handle: SessionHandle) {
        val tabs = JBTabbedPane()
        val grids = mutableListOf<Pair<String, DataGrid>>()
        init { tabs.addTab("Output", AllIcons.Debugger.Console, OutputLog.getInstance(project).console(handle.title).component) }
    }

    init {
        tree.isRootVisible = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                    is SessionHandle -> {
                        icon = AllIcons.Nodes.Console
                        append(o.title)
                        o.lastMillis?.let { append("  $it ms", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                        if (o.session.transactionOpen) append("  tx", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                    is String -> { icon = if (o == "Database") AllIcons.Nodes.Folder else AllIcons.Nodes.DataTables; append(o) }
                }
            }
        }
        tree.addTreeSelectionListener { (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject.let { if (it is SessionHandle) select(it.key) } }
        val split = Splitter(false, 0.25f)
        split.firstComponent = JBScrollPane(tree)
        split.secondComponent = right
        add(split, BorderLayout.CENTER)
        // vertical toolbar on the left: commit / rollback of the selected session
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val txGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(listOfNotNull(am.getAction("MyDb.Commit"), am.getAction("MyDb.Rollback")))
        val toolbar = am.createActionToolbar(TOOLBAR_PLACE, txGroup, false).also { it.targetComponent = this }
        add(toolbar.component, BorderLayout.WEST)
        project.messageBus.connect(this).subscribe(ConsoleService.TOPIC, ConsoleService.Listener { rebuild() })
        rebuild()
    }

    private fun rebuild() {
        root.removeAllChildren()
        ConsoleService.getInstance(project).all().groupBy { it.dataSource.name }.forEach { (ds, handles) ->
            val dsNode = DefaultMutableTreeNode(ds)
            handles.forEach { dsNode.add(DefaultMutableTreeNode(it)) }
            root.add(dsNode)
        }
        treeModel.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    /** Every result grid with its tab title (data compare). */
    fun allGrids(): List<Pair<String, DataGrid>> = sessionViews.values.flatMap { it.grids }

    fun view(handle: SessionHandle): SessionView = sessionViews.getOrPut(handle.key) { SessionView(handle) }

    fun select(key: String) {
        val v = sessionViews[key] ?: ConsoleService.getInstance(project).existing(key)?.let(::view) ?: return
        shownKey = key
        right.removeAll()
        right.add(v.tabs, BorderLayout.CENTER)
        right.revalidate(); right.repaint()
        revalidate(); repaint()
    }

    /** Replaces the session's result tabs with new results. */
    fun showResults(handle: SessionHandle, results: List<ConsoleRunner.ResultTab>) {
        val v = view(handle)
        if (!dev.teo.mydb.plugin.settings.MyDbSettings.get().resultsInNewTab) {
            while (v.tabs.tabCount > 1) v.tabs.removeTabAt(1)
            v.grids.forEach { Disposer.dispose(it.second) }
            v.grids.clear()
        }
        for (r in results) {
            val grid = createGrid(handle, r.page, r.query, r.edit, r.counter)
            v.grids += r.title to grid
            v.tabs.addTab(r.title, AllIcons.Nodes.DataTables, dev.teo.mydb.plugin.grid.GridToolbar.component(grid))
        }
        v.tabs.selectedIndex = if (results.isEmpty()) 0 else v.tabs.tabCount - 1
        v.tabs.revalidate(); v.tabs.repaint()
        revalidate(); repaint() // the tool window may have just been created
        rebuild()
        select(handle.key)
    }

    /** Adds a "Query plan" tab. */
    fun showPlan(handle: SessionHandle, plan: dev.teo.mydb.core.plan.QueryPlan, rerun: (() -> Unit)? = null) {
        val v = view(handle)
        (1 until v.tabs.tabCount).firstOrNull { v.tabs.getTitleAt(it) == "Query Plan" }?.let { v.tabs.removeTabAt(it) }
        v.tabs.addTab("Query Plan", AllIcons.Actions.Preview, PlanView(project, plan, rerun))
        v.tabs.selectedIndex = v.tabs.tabCount - 1
        rebuild(); select(handle.key)
    }

    fun showOutput(handle: SessionHandle) {
        val v = view(handle); v.tabs.selectedIndex = 0; select(handle.key)
    }

    fun createGrid(handle: SessionHandle, page: ResultPage, query: PageQuery, edit: EditTarget?, counter: dev.teo.mydb.plugin.grid.RowCounter? = null): DataGrid =
        dev.teo.mydb.plugin.grid.GridFactory.create(project, handle.session, page, query, edit, this, counter, OutputLog.getInstance(project).gridLog(handle.title), handle.key)

    /** Session of the selected tree node, or of the session whose tabs are shown. */
    fun selectedSession(): SessionHandle? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionHandle
            ?: shownKey?.let { ConsoleService.getInstance(project).existing(it) }

    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
        sink[SELECTED_SESSION] = selectedSession()
    }

    override fun dispose() {}

    companion object {
        const val TOOLBAR_PLACE = "MyDbServicesToolbar"
        val SELECTED_SESSION = com.intellij.openapi.actionSystem.DataKey.create<SessionHandle>("mydb.services.session")
    }

    fun component(): JComponent = this
}
