package dev.teo.mydb.plugin.console

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import dev.teo.mydb.core.plan.PlanNode
import dev.teo.mydb.core.plan.QueryPlan
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode

/** Query plan as a tree table with a property panel. */
class PlanView(private val project: Project, private val plan: QueryPlan, private val rerun: (() -> Unit)? = null) : JPanel(BorderLayout()) {
    private val props = DefaultTableModel(arrayOf("Property", "Value"), 0)
    lateinit var table: TreeTable
        private set

    init {
        val root = node(plan.root)
        fun num(d: Double?) = d?.let { if (it == Math.floor(it)) it.toLong().toString() else "%.3f".format(it).trimEnd('0').trimEnd('.') } ?: ""
        val cols = mutableListOf<ColumnInfo<*, *>>(TreeColumnInfo("Operation"),
            col("Parameters") { it.parameters }, col("Rows") { num(it.rows) })
        if (plan.analyzed) { cols += col("Actual rows") { num(it.actualRows) }; cols += col("Actual total") { num(it.actualTotalTime) } }
        cols += col("Total cost") { num(it.totalCost) }
        cols += col("Startup cost") { num(it.startupCost) }
        if (plan.analyzed) cols += col("Actual startup") { num(it.actualStartupTime) }
        cols += col("Raw description") { rawDescription(it) }
        val model = ListTreeTableModelOnColumns(root, cols.toTypedArray())
        val table = TreeTable(model)
        table.tree.isRootVisible = true
        table.setTreeCellRenderer(object : com.intellij.ui.ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val n = (value as? DefaultMutableTreeNode)?.userObject as? PlanNode ?: return
                icon = when {
                    n.serverNodeType.contains("Scan") -> AllIcons.Nodes.DataTables
                    n.serverNodeType.contains("Join") || n.serverNodeType == "Nested Loop" -> AllIcons.Nodes.Related
                    else -> AllIcons.Nodes.Method
                }
                append(n.operation)
            }
        })
        expandAll(table)
        table.columnModel.getColumn(0).preferredWidth = com.intellij.util.ui.JBUI.scale(260)
        this.table = table
        table.selectionModel.addListSelectionListener {
            val n = (table.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? PlanNode ?: return@addListSelectionListener
            props.rowCount = 0
            n.properties.forEach { (k, v) -> props.addRow(arrayOf(k, v)) }
        }
        val split = Splitter(false, 0.72f)
        split.firstComponent = JBScrollPane(table)
        split.secondComponent = JBScrollPane(JBTable(props))
        add(split, BorderLayout.CENTER)
        val actions = DefaultActionGroup(object : DumbAwareAction("Run Again", null, AllIcons.Actions.Rerun) {
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = rerun != null }
            override fun actionPerformed(e: AnActionEvent) { rerun?.invoke() }
        }, object : DumbAwareAction("Copy Plan", null, AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) =
                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(asText()))
        }, object : DumbAwareAction("Show Diagram", null, AllIcons.FileTypes.Diagram) {
            override fun actionPerformed(e: AnActionEvent) = dev.teo.mydb.plugin.diagram.DiagramEditors.openPlan(project, plan)
        }, moreGroup())
        val tb = ActionManager.getInstance().createActionToolbar("MyDbPlan", actions, false)
        tb.targetComponent = this
        add(tb.component, BorderLayout.WEST)
        table.tree.setSelectionRow(0)
    }

    /** "More": expand/collapse and the raw description column. */
    private fun moreGroup() = DefaultActionGroup("More", true).apply {
        templatePresentation.icon = AllIcons.Actions.More
        add(object : DumbAwareAction("Expand All") { override fun actionPerformed(e: AnActionEvent) = expandAll(table) })
        add(object : DumbAwareAction("Collapse All") {
            override fun actionPerformed(e: AnActionEvent) { var i = table.tree.rowCount - 1; while (i > 0) { table.tree.collapseRow(i); i-- } }
        })
        add(object : com.intellij.openapi.project.DumbAwareToggleAction("Show Raw Description") {
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = rawColumnShown()
            override fun setSelected(e: AnActionEvent, state: Boolean) { showRawColumn(state) }
        })
    }

    private var rawColumn: javax.swing.table.TableColumn? = null
    fun rawColumnShown() = rawColumn == null
    fun showRawColumn(show: Boolean) {
        val cm = table.columnModel
        if (!show && rawColumn == null) { rawColumn = cm.getColumn(cm.columnCount - 1); cm.removeColumn(rawColumn) }
        else if (show && rawColumn != null) { cm.addColumn(rawColumn); rawColumn = null }
    }

    /** Operation names of the visible rows, depth-first (tests). */
    fun shownOperations(): List<String> = (0 until table.tree.rowCount).map {
        ((table.tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject as PlanNode).operation
    }

    /** Plan as indented text: operation, parameters, rows and costs (Copy Plan). */
    fun asText(): String = buildString {
        fun walk(n: PlanNode, depth: Int) {
            append("  ".repeat(depth)).append(n.operation)
            if (n.parameters.isNotEmpty()) append("  ").append(n.parameters)
            append("  rows=").append(n.rows ?: "").append(" cost=").append(n.startupCost ?: "").append("..").append(n.totalCost ?: "").append('\n')
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(plan.root, 0)
    }

    private fun expandAll(t: TreeTable) {
        var i = 0
        while (i < t.tree.rowCount) { t.tree.expandRow(i); i++ }
    }

    private fun rawDescription(n: PlanNode) = n.properties.filterKeys { it !in SHOWN }.entries.joinToString(", ") { "${it.key} = ${it.value}" }

    private fun node(p: PlanNode): DefaultMutableTreeNode = DefaultMutableTreeNode(p).also { n -> p.children.forEach { n.add(node(it)) } }

    private fun col(name: String, f: (PlanNode) -> String) = object : ColumnInfo<DefaultMutableTreeNode, String>(name) {
        override fun valueOf(item: DefaultMutableTreeNode?) = (item?.userObject as? PlanNode)?.let(f) ?: ""
    }

    private companion object {
        val SHOWN = setOf("Node Type", "Operation", "Plan Rows", "Total Cost", "Startup Cost", "Plan Width", "Actual Rows",
            "Actual Total Time", "Actual Startup Time", "Actual Loops", "Relation Name", "Alias", "Schema")
    }
}
