package dev.teo.mydb.plugin.compare

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.diff.DiffItem
import dev.teo.mydb.core.diff.DiffStatus
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.sql.SqlFileType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Migration dialog: source and target objects side by side with a checkbox per
 * difference, the migration script of the checked items and the DDL diff of the selected object.
 */
class SchemaCompareDialog(private val project: Project, private val compare: SchemaCompare) : DialogWrapper(project, true) {
    private var showIdentical = false
    private var rows: List<CompareRow> = emptyList()
    private val tableModel = RowsModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).maxWidth = JBUI.scale(28)
        setDefaultRenderer(String::class.java, StatusRenderer())
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
    }
    private val propsModel = PropsModel()
    private val propsTable = JBTable(propsModel).apply { setDefaultRenderer(String::class.java, PropsRenderer()) }
    private val scriptDocument = EditorFactory.getInstance().createDocument("")
    private val scriptEditor = EditorFactory.getInstance().createViewer(scriptDocument, project, EditorKind.PREVIEW).also {
        (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
    }
    private val ddlDiff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private var ddlTabTitle: ((String) -> Unit)? = null

    private fun reload() {
        rows = compare.tree(showIdentical)
        tableModel.fireTableDataChanged()
        title = "Migrate: ${compare.source.title} → ${compare.target.title}"
        refreshScript()
        rows.indexOfFirst { it is CompareRow.Item }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) }
    }

    private fun refreshScript() {
        val text = compare.script()
        ApplicationManager.getApplication().runWriteAction { scriptDocument.setText(text) }
        executeAction.isEnabled = text.isNotBlank()
        consoleAction.isEnabled = text.isNotBlank()
    }

    /** Selected object, or null for a folder row. */
    fun selectedItem(): DiffItem? = (rows.getOrNull(table.selectedRow) as? CompareRow.Item)?.item

    /** Rows of the properties tab (tests). */
    fun shownProperties(): List<PropertyRow> = propsModel.rows

    private fun showSelected() {
        val item = selectedItem()
        propsModel.rows = item?.let(compare::properties).orEmpty()
        propsModel.fireTableDataChanged()
        showDdlDiff()
    }

    private fun showDdlDiff() {
        val item = selectedItem() ?: return
        val (left, right) = compare.ddl(item)
        val f = DiffContentFactory.getInstance()
        ddlTabTitle?.invoke("DDL Diff (${compare.ddlDifferences(item)})")
        ddlDiff.setRequest(SimpleDiffRequest("DDL of ${item.name}", f.create(project, left, SqlFileType), f.create(project, right, SqlFileType),
            compare.source.title, compare.target.title))
    }

    private val executeAction = object : DialogWrapperAction("Execute") {
        override fun doAction(e: ActionEvent?) {
            isEnabled = false
            object : Task.Backgroundable(project, "Migrating ${compare.target.title}", true) {
                private var error: String? = null
                override fun run(indicator: ProgressIndicator) { error = compare.execute() }
                override fun onSuccess() {
                    reload()
                    if (error == null) close(OK_EXIT_CODE) else setErrorText(error)
                }
                override fun onThrowable(t: Throwable) { setErrorText(t.message ?: t.toString()); isEnabled = true }
            }.queue()
        }
    }

    /** Puts the script into a new console of the target data source without running it. */
    private val consoleAction = object : DialogWrapperAction("Open in New Query Console") {
        override fun doAction(e: ActionEvent?) { compare.openInConsole(); close(OK_EXIT_CODE) }
    }

    init {
        init()
        reload()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, consoleAction, executeAction)

    override fun createCenterPanel(): JComponent {
        val group = DefaultActionGroup(
            object : DumbAwareAction("Swap Source and Target", null, AllIcons.Diff.ArrowLeftRight) {
                override fun actionPerformed(e: AnActionEvent) { compare.swap(); reload() }
            },
            object : DumbAwareAction("Check All", null, AllIcons.Actions.Selectall) {
                override fun actionPerformed(e: AnActionEvent) { compare.setAllChecked(true); tableModel.fireTableDataChanged(); refreshScript() }
            },
            object : DumbAwareAction("Uncheck All", null, AllIcons.Actions.Unselectall) {
                override fun actionPerformed(e: AnActionEvent) { compare.setAllChecked(false); tableModel.fireTableDataChanged(); refreshScript() }
            },
            object : DumbAwareToggleAction("Show Identical Items", null, AllIcons.Actions.ToggleVisibility) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = showIdentical
                override fun setSelected(e: AnActionEvent, state: Boolean) { showIdentical = state; reload() }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("MyDbSchemaCompare", group, true).also { it.targetComponent = table }
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        val tabs = JBTabbedPane().apply {
            addTab("Script Preview", scriptEditor.component)
            addTab("DDL Diff", ddlDiff.component)
            addTab("Object Properties Diff", JBScrollPane(propsTable))
        }
        ddlTabTitle = { tabs.setTitleAt(1, it) }
        return Splitter(true, 0.5f).apply {
            firstComponent = top
            secondComponent = tabs
            preferredSize = Dimension(JBUI.scale(1000), JBUI.scale(680))
        }
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(scriptEditor)
        super.dispose()
    }

    private inner class RowsModel : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("", compare.source.title, compare.target.title)[column]
        override fun getColumnClass(columnIndex: Int) = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0 && when (val r = rows[row]) {
            is CompareRow.Item -> r.item.status != DiffStatus.SAME
            is CompareRow.Folder -> r.members.any { it.status != DiffStatus.SAME }
        }
        override fun getValueAt(row: Int, column: Int): Any? {
            val r = rows[row]
            val indent = "    ".repeat(r.depth)
            if (r is CompareRow.Folder) return when (column) {
                0 -> compare.isChecked(r)
                else -> "$indent▾ ${r.label} (${r.members.size})"
            }
            val item = (r as CompareRow.Item).item
            return when (column) {
                0 -> compare.isChecked(item)
                1 -> if (item.status == DiffStatus.ONLY_IN_TARGET) "" else "$indent${item.kind} ${item.name}" + (item.source?.takeIf { item.kind == "column" }?.let { "  $it" } ?: "")
                else -> if (item.status == DiffStatus.ONLY_IN_SOURCE) "" else "$indent${item.kind} ${item.name}" + (item.target?.takeIf { item.kind == "column" }?.let { "  $it" } ?: "")
            }
        }
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            compare.setChecked(rows[row], value == true)
            fireTableDataChanged()
            refreshScript()
        }
    }

    private inner class PropsModel : AbstractTableModel() {
        var rows: List<PropertyRow> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Property", compare.source.title, compare.target.title)[column]
        override fun getColumnClass(columnIndex: Int) = String::class.java
        override fun getValueAt(row: Int, column: Int): Any? = rows[row].let { listOf(it.name, it.source.orEmpty(), it.target.orEmpty())[column] }
    }

    /** Differing properties in the "changed" colour. */
    private inner class PropsRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) foreground = if (propsModel.rows[row].differs) FileStatus.MODIFIED.color else table.foreground
            return c
        }
    }

    /** Green: only in source (created), red: only in target (dropped), blue: changed. */
    private inner class StatusRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val status = when (val r = rows[row]) {
                is CompareRow.Item -> r.item.status
                is CompareRow.Folder -> r.members.map { it.status }.distinct().singleOrNull() ?: DiffStatus.CHANGED
            }
            if (!isSelected) foreground = when (status) {
                DiffStatus.ONLY_IN_SOURCE -> FileStatus.ADDED.color
                DiffStatus.ONLY_IN_TARGET -> FileStatus.DELETED.color
                DiffStatus.CHANGED -> FileStatus.MODIFIED.color
                DiffStatus.SAME -> table.foreground
            }
            return c
        }
    }
}
