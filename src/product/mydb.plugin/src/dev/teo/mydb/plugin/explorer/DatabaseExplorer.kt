package dev.teo.mydb.plugin.explorer

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.teo.mydb.core.model.*
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class DatabaseExplorerFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DatabaseExplorerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object { const val ID = "MyDB Explorer" }
}

/**
 * The database explorer tree. Rebuilt from the models on every change while keeping
 * the expansion and selection state.
 */
class DatabaseExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), UiDataProvider, com.intellij.openapi.Disposable {
    private val root = DefaultMutableTreeNode("root")
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    private val filterField = com.intellij.ui.SearchTextField(false)
    /** Name filter: only matching objects and their parents are shown. */
    var filter: String = ""
        private set


    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        // empty state: shortcuts for the supported DBMS and the full data source list
        tree.emptyText.apply {
            text = "No data sources"
            appendLine("Create a data source:")
            for (dbms in dev.teo.mydb.plugin.util.Dialects.supported) {
                appendLine(dev.teo.mydb.plugin.util.Dialects.icon(dbms), dbms.displayName, com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, java.awt.event.ActionListener {
                    dev.teo.mydb.plugin.datasource.DataSourceDialog(project, null, dbms).show()
                })
            }
            appendLine("All data sources…", com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, java.awt.event.ActionListener {
                dev.teo.mydb.plugin.datasource.DataSourceDialog(project, null).show()
            })
        }
        TreeSpeedSearch.installOn(tree, true) { path -> ((path.lastPathComponent as DefaultMutableTreeNode).userObject as? ExplorerNode)?.label ?: "" }
        filterField.isVisible = false
        filterField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { filter = filterField.text.trim(); refreshTree() }
        })
        setContent(javax.swing.JPanel(java.awt.BorderLayout()).apply {
            add(filterField, java.awt.BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree), java.awt.BorderLayout.CENTER)
        })
        val am = ActionManager.getInstance()
        (am.getAction("MyDb.Explorer.Toolbar") as? ActionGroup)?.let { g ->
            val tb = am.createActionToolbar("MyDbExplorer", g, true)
            tb.targetComponent = this
            toolbar = tb.component
        }
        (am.getAction("MyDb.Explorer.Popup") as? ActionGroup)?.let { PopupHandler.installPopupMenu(tree, it, "MyDbExplorerPopup") }
        // explorer shortcuts of are keymap entries (plugin.xml): the actions are enabled only for an explorer selection
        tree.addTreeSelectionListener {
            project.messageBus.syncPublisher(dev.teo.mydb.plugin.generator.ExplorerSelectionListener.TOPIC).selectionChanged(selected())
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // a click on the `shown/total` badge opens the schema checklist
                if (e.clickCount == 1 && onBadge(e)) {
                    ActionManager.getInstance().getAction("MyDb.ChooseSchemas")?.let { a ->
                        com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(a, tree, "MyDbExplorer", e, null)
                    }
                    return
                }
                if (e.clickCount == 2) (selected().singleOrNull() as? ExplorerNode.Table)?.let {
                    if (dev.teo.mydb.plugin.settings.MyDbSettings.get().explorerDoubleClick == dev.teo.mydb.plugin.settings.MyDbSettings.DoubleClickTarget.DDL)
                        ExplorerActions.openDdl(project, it.config, it.database, it.schema, it.table)
                    else ExplorerActions.openTable(project, it)
                }
            }
        })
        val bus = project.messageBus.connect(this)
        bus.subscribe(ModelService.TOPIC, ModelService.Listener { refreshTree() })
        bus.subscribe(DataSourceStorage.TOPIC, DataSourceStorage.Listener { refreshTree() })
        bus.subscribe(ConsoleService.TOPIC, ConsoleService.Listener { tree.repaint() })
        refreshTree()
    }

    val filterShown get() = filterField.isVisible

    fun showFilter(show: Boolean) {
        filterField.isVisible = show
        if (show) filterField.requestFocusInWindow() else { filterField.text = ""; filter = ""; refreshTree() }
        revalidate(); repaint()
    }

    fun setFilter(text: String) { filterField.isVisible = true; filterField.text = text }

    private fun matches(name: String) = filter.isEmpty() || name.contains(filter, ignoreCase = true)

    private fun onBadge(e: MouseEvent): Boolean {
        val path = tree.getPathForLocation(e.x, e.y) ?: return false
        val n = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode ?: return false
        if (n !is ExplorerNode.DataSource && n !is ExplorerNode.Database) return false
        val bounds = tree.getPathBounds(path) ?: return false
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, false, true, false, tree.getRowForPath(path), false)
            as? com.intellij.ui.SimpleColoredComponent ?: return false
        renderer.size = bounds.size
        renderer.doLayout()
        val i = renderer.findFragmentAt(e.x - bounds.x)
        return i >= 0 && renderer.getFragmentTag(i) == BADGE
    }

    fun selected(): List<ExplorerNode> = tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[ExplorerDataKeys.SELECTED] = selected()
        sink[ExplorerDataKeys.PANEL] = this
    }

    fun refreshTree() {
        // expansion and selection survive a refresh; nodes are matched by their label path
        // because node objects change when counts or metadata change
        val expanded = TreeUtil.collectExpandedPaths(tree).map(::labelPath)
        val selected = tree.selectionPaths.orEmpty().map(::labelPath)
        root.removeAllChildren()
        val sessions = ConsoleService.getInstance(project).all().map { it.dataSource.id }.toSet()
        for ((config, m) in ModelService.getInstance(project).allModels()) {
            val dbs = m?.databases.orEmpty()
            val shownDbs = dbs.filter { it.schemas.isNotEmpty() }
            // MySQL databases are schemas: they hang directly under the data source, with the badge counting them
            if (config.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL && dbs.size == 1) {
                val db = dbs.single()
                val userSchemas = db.allSchemaNames.filterNot { it in MYSQL_SYSTEM }
                val dsNode = node(ExplorerNode.DataSource(config, db.schemas.size, userSchemas.size, config.id in sessions))
                for (s in db.schemas) schemaNode(config, db.name, s).takeIf { filter.isEmpty() || it.childCount > 0 }?.let(dsNode::add)
                root.add(dsNode)
                continue
            }
            val dsNode = node(ExplorerNode.DataSource(config, shownDbs.size, dbs.size, config.id in sessions))
            for (db in shownDbs) {
                val userSchemas = db.allSchemaNames.filterNot { it.startsWith("pg_") || it == "information_schema" }
                val dbNode = node(ExplorerNode.Database(config, db, db.schemas.size, userSchemas.size))
                for (s in db.schemas) schemaNode(config, db.name, s).takeIf { filter.isEmpty() || it.childCount > 0 }?.let(dbNode::add)
                dsNode.add(dbNode)
            }
            root.add(dsNode)
        }
        model.reload()
        expanded.sortedBy { it.size }.forEach { p -> findPath(p)?.let(tree::expandPath) }
        selected.mapNotNull(::findPath).takeIf { it.isNotEmpty() }?.let { tree.selectionPaths = it.toTypedArray() }
        if (filter.isNotEmpty()) TreeUtil.expandAll(tree)
        else if (expanded.isEmpty() && root.childCount > 0) TreeUtil.expand(tree, 1)
    }

    private fun labelPath(path: javax.swing.tree.TreePath): List<String> =
        path.path.drop(1).map { ((it as DefaultMutableTreeNode).userObject as? ExplorerNode)?.let { n -> n::class.simpleName + ":" + n.label } ?: it.toString() }

    private fun findPath(labels: List<String>): javax.swing.tree.TreePath? {
        var node: DefaultMutableTreeNode = root
        for (l in labels) {
            node = node.children().asSequence().map { it as DefaultMutableTreeNode }
                .firstOrNull { c -> (c.userObject as? ExplorerNode)?.let { n -> n::class.simpleName + ":" + n.label } == l } ?: return null
        }
        return javax.swing.tree.TreePath(node.path)
    }

    private fun schemaNode(config: dev.teo.mydb.core.datasource.DataSourceConfig, db: String, s: DbSchema): DefaultMutableTreeNode {
        val n = node(ExplorerNode.Schema(config, db, s))
        fun folder(kind: FolderKind, items: List<DefaultMutableTreeNode>) {
            if (items.isEmpty()) return // no empty folders
            val f = node(ExplorerNode.Folder(config, db, s.name, kind, items.size))
            items.forEach(f::add)
            n.add(f)
        }
        val byKind = s.tables.groupBy { it.kind }
        // with a filter: tables whose name or a column name matches
        fun shown(t: DbTable) = matches(t.name) || t.columns.any { matches(it.name) }
        folder(FolderKind.TABLES, (byKind[RelationKind.TABLE].orEmpty() + byKind[RelationKind.PARTITIONED_TABLE].orEmpty() + byKind[RelationKind.FOREIGN_TABLE].orEmpty())
            .filter(::shown).sortedBy { it.name }.map { tableNode(config, db, s.name, it) })
        folder(FolderKind.VIEWS, byKind[RelationKind.VIEW].orEmpty().filter(::shown).map { tableNode(config, db, s.name, it) })
        folder(FolderKind.MATERIALIZED_VIEWS, byKind[RelationKind.MATERIALIZED_VIEW].orEmpty().filter(::shown).map { tableNode(config, db, s.name, it) })
        folder(FolderKind.ROUTINES, s.routines.filter { matches(it.name) }.map { node(ExplorerNode.Routine(config, s.name, it)) })
        folder(FolderKind.SEQUENCES, s.sequences.filter { matches(it.name) }.map { node(ExplorerNode.Sequence(config, s.name, it)) })
        return n
    }

    private fun tableNode(config: dev.teo.mydb.core.datasource.DataSourceConfig, db: String, schema: String, t: DbTable): DefaultMutableTreeNode {
        val n = node(ExplorerNode.Table(config, db, schema, t))
        fun folder(kind: FolderKind, items: List<DefaultMutableTreeNode>) {
            if (items.isEmpty()) return
            val f = node(ExplorerNode.Folder(config, db, schema, kind, items.size, t.name))
            items.forEach(f::add)
            n.add(f)
        }
        // a table matched by name shows everything; one matched by a column shows only matching columns
        val byName = matches(t.name)
        folder(FolderKind.COLUMNS, t.columns.filter { byName || matches(it.name) }.map { node(ExplorerNode.Column(config, db, schema, t, it)) })
        if (!t.isView && byName) {
            folder(FolderKind.KEYS, (listOfNotNull(t.primaryKey) + t.uniqueKeys).map { node(ExplorerNode.Key(config, t, it)) })
            folder(FolderKind.FOREIGN_KEYS, t.foreignKeys.map { node(ExplorerNode.ForeignKey(config, t, it)) })
            folder(FolderKind.INDEXES, t.indexes.map { node(ExplorerNode.Index(config, t, it)) })
            folder(FolderKind.CHECKS, t.checks.map { node(ExplorerNode.Key(config, t, DbKey(it.name, emptyList(), false))) })
        }
        return n
    }

    private fun node(o: ExplorerNode) = DefaultMutableTreeNode(o)

    override fun dispose() {}

    private companion object {
        const val BADGE = "schema-badge"
        val MYSQL_SYSTEM = setOf("information_schema", "mysql", "performance_schema", "sys")
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val n = (value as? DefaultMutableTreeNode)?.userObject as? ExplorerNode ?: return
            val details = dev.teo.mydb.plugin.settings.MyDbSettings.get().explorerShowDetails
            val gray = SimpleTextAttributes.GRAYED_ATTRIBUTES
            fun detail(text: String) { if (details) append(text, gray) }
            when (n) {
                is ExplorerNode.DataSource -> {
                    icon = dev.teo.mydb.plugin.util.Dialects.icon(n.config.dbms)
                    n.config.color?.let { c -> runCatching { java.awt.Color.decode(c) }.getOrNull() }?.let { append("● ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, it)) }
                    append(n.label); append("  ${n.shown}/${n.total}", gray, BADGE)
                }
                is ExplorerNode.Database -> { icon = AllIcons.Nodes.DataSchema; append(n.label); append("  ${n.shown}/${n.total}", gray, BADGE) }
                is ExplorerNode.Schema -> { icon = AllIcons.Nodes.Package; append(n.label) }
                is ExplorerNode.Folder -> { icon = AllIcons.Nodes.Folder; append(n.label); detail("  ${n.count}") }
                is ExplorerNode.Table -> { icon = if (n.table.isView) AllIcons.Nodes.DataSchema else AllIcons.Nodes.DataTables; append(n.label) }
                is ExplorerNode.Column -> {
                    val pk = n.table.primaryKey?.columns?.contains(n.column.name) == true
                    val fk = n.table.foreignKeys.any { n.column.name in it.columns }
                    icon = when { pk -> AllIcons.Nodes.DataColumn; fk -> AllIcons.Nodes.Related; else -> AllIcons.Nodes.Field }
                    append(n.label); detail("  ${n.detail.let { if (it.length > 45) it.take(44) + "…" else it }}")
                }
                is ExplorerNode.Key -> { icon = AllIcons.Nodes.Static; append(n.label); detail("  (${n.key.columns.joinToString(", ")})") }
                is ExplorerNode.ForeignKey -> { icon = AllIcons.Nodes.Related; append(n.label, if (n.fk.virtual) SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    detail("  (${n.fk.columns.joinToString(", ")}) → ${n.fk.refTable}${if (n.fk.virtual) "  virtual" else ""}") }
                is ExplorerNode.Index -> { icon = AllIcons.Nodes.Tag; append(n.label); detail("  (${n.index.columns.joinToString(", ")})") }
                is ExplorerNode.Sequence -> { icon = AllIcons.Nodes.Static; append(n.label) }
                is ExplorerNode.Routine -> { icon = AllIcons.Nodes.Function; append(n.label) }
            }
        }
    }
}
