package dev.teo.mydb.plugin.objects

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.plugin.sql.SqlFileType
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Create / modify table: object tree on the left (table, columns, keys, foreign keys),
 * the selected object's form on the right and the live DDL preview with a problem count below.
 */
class TableDialog(
    private val project: Project,
    private val config: DataSourceConfig,
    private val database: String,
    val draft: TableDraft,
) : DialogWrapper(project, true) {

    private val root = DefaultMutableTreeNode(draft)
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val forms = JPanel(CardLayout())
    private val previewDocument = EditorFactory.getInstance().createDocument("")
    private val preview = EditorFactory.getInstance().createViewer(previewDocument, project, EditorKind.PREVIEW).also {
        (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
    }
    private val problemsLabel = JBLabel()
    private var updating = false

    private enum class Folder(val title: String) { COLUMNS("Columns"), KEYS("Keys"), FOREIGN_KEYS("Foreign Keys"), INDEXES("Indexes"), CHECKS("Checks") }

    init {
        title = if (draft.isNew) "Create Table" else "Modify Table ${draft.schema.name}.${draft.name}"
        tree.cellRenderer = Renderer()
        tree.addTreeSelectionListener { showForm() }
        init()
        rebuildTree(select = draft)
        refreshPreview()
    }

    // ---- tree ------------------------------------------------------------------------------------

    private fun rebuildTree(select: Any?) {
        root.removeAllChildren()
        val folders = mapOf(
            Folder.COLUMNS to draft.columns,
            Folder.KEYS to listOfNotNull(draft.primaryKey) + draft.uniqueKeys,
            Folder.FOREIGN_KEYS to draft.foreignKeys,
            Folder.INDEXES to draft.indexes,
            Folder.CHECKS to draft.checks,
        )
        for ((folder, items) in folders) {
            val node = DefaultMutableTreeNode(folder)
            items.forEach { node.add(DefaultMutableTreeNode(it)) }
            root.add(node)
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
        val target = TreeUtil.findNode(root) { it.userObject === select } ?: root
        tree.selectionPath = TreePath(target.path)
    }

    private fun selected(): Any? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is TableDraft -> { icon = AllIcons.Nodes.DataTables; append(o.name) }
                is Folder -> {
                    icon = AllIcons.Nodes.Folder; append(o.title)
                    val n = (value as DefaultMutableTreeNode).childCount
                    if (n > 0) append("  $n", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ColumnDraft -> { icon = AllIcons.Nodes.DataColumn; append(o.name); append("  ${o.type}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                is KeyDraft -> { icon = if (o.primary) AllIcons.Nodes.DataTables else AllIcons.Nodes.DataColumn; append(o.name); if (o.autoName) append("  auto", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                is FkDraft -> { icon = AllIcons.Nodes.Related; append(o.name); if (o.autoName) append("  auto", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                is IndexDraft -> {
                    icon = AllIcons.Nodes.DataSchema; append(o.name)
                    append("  ${o.columns.joinToString(", ")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CheckDraft -> { icon = AllIcons.General.InspectionsOK; append(o.name); append("  ${o.expression}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }

    // ---- forms -----------------------------------------------------------------------------------

    /** Applies an edit, recomputes automatic names and refreshes tree labels and the preview. */
    private fun changed() {
        if (updating) return
        draft.refreshAutoNames()
        treeModel.nodeChanged(root)
        TreeUtil.treeNodeTraverser(root).forEach { treeModel.nodeChanged(it as DefaultMutableTreeNode) }
        refreshPreview()
    }

    private fun boundText(initial: String?, apply: (String) -> Unit) = JBTextField(initial.orEmpty()).apply {
        document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) { apply(text); changed() } })
    }

    private fun boundCheck(textValue: String, initial: Boolean, apply: (Boolean) -> Unit) = JBCheckBox(textValue, initial).apply { addActionListener { apply(isSelected); changed() } }

    private fun showForm() {
        updating = true
        val form: JComponent = when (val o = selected()) {
            is TableDraft -> panel {
                row("Name:") { cell(boundText(o.name) { o.name = it }).align(AlignX.FILL) }
                row("Comment:") { cell(boundText(o.comment) { o.comment = it }).align(AlignX.FILL) }
            }
            is ColumnDraft -> panel {
                row("Name:") { cell(boundText(o.name) { o.name = it }).align(AlignX.FILL) }
                row("Comment:") { cell(boundText(o.comment) { o.comment = it }).align(AlignX.FILL) }
                row("Data type:") {
                    cell(ComboBox(draft.typeChoices.toTypedArray()).apply {
                        isEditable = true; selectedItem = o.type
                        addActionListener { (selectedItem as? String)?.let { o.type = it.trim(); changed() } }
                    }).align(AlignX.FILL)
                }
                row { cell(boundCheck("Not null", o.notNull) { o.notNull = it }) }
                row("Default:") { cell(boundText(o.default?.takeUnless { o.identity }) { o.default = it.ifBlank { null } }).align(AlignX.FILL) }
                row("Generated:") {
                    cell(boundText(o.generated) { o.generated = it.ifBlank { null } }.apply { emptyText.text = "expression, stored" }).align(AlignX.FILL)
                }
                row { cell(boundCheck("Identity (serial)", o.identity) { o.identity = it }) }
            }
            is KeyDraft -> keyForm(o)
            is FkDraft -> fkForm(o)
            is IndexDraft -> indexForm(o)
            is CheckDraft -> panel {
                row("Name:") { cell(boundText(o.name) { draft.renameCheck(o, it) }).align(AlignX.FILL) }
                row("Expression:") { cell(boundText(o.expression) { o.expression = it }).align(AlignX.FILL) }
            }
            else -> JPanel()
        }
        forms.removeAll()
        forms.add(JBScrollPane(form), "form")
        forms.revalidate(); forms.repaint()
        updating = false
    }

    private fun keyForm(k: KeyDraft): JComponent {
        val columns = CheckBoxList<String>().apply {
            draft.columns.forEach { c -> addItem(c.name, c.name, c.name in k.columns) }
            setCheckBoxListListener { index, value ->
                val name = getItemAt(index) ?: return@setCheckBoxListListener
                if (value) { if (name !in k.columns) k.columns += name } else k.columns.remove(name)
                changed()
            }
        }
        return panel {
            row("Name:") { cell(boundText(k.name) { draft.renameKey(k, it) }).align(AlignX.FILL) }
            row { cell(boundCheck("Primary", k.primary) { primary -> switchPrimary(k, primary) }) }
            row("Columns:") { cell(JBScrollPane(columns).apply { preferredSize = Dimension(JBUI.scale(260), JBUI.scale(140)) }) }
        }
    }

    private fun indexForm(i: IndexDraft): JComponent {
        val columns = CheckBoxList<String>().apply {
            draft.columns.forEach { c -> addItem(c.name, c.name, c.name in i.columns) }
            setCheckBoxListListener { index, value ->
                val name = getItemAt(index) ?: return@setCheckBoxListListener
                if (value) { if (name !in i.columns) i.columns += name } else i.columns.remove(name)
                changed()
            }
        }
        return panel {
            row("Name:") { cell(boundText(i.name) { draft.renameIndex(i, it) }).align(AlignX.FILL) }
            row { cell(boundCheck("Unique", i.unique) { i.unique = it }) }
            row("Method:") {
                cell(ComboBox(arrayOf("btree", "hash", "gist", "gin", "brin", "spgist")).apply {
                    selectedItem = i.method
                    addActionListener { i.method = selectedItem as String; changed() }
                })
            }
            row("Columns:") { cell(JBScrollPane(columns).apply { preferredSize = Dimension(JBUI.scale(260), JBUI.scale(140)) }) }
            row("Where:") { cell(boundText(i.predicate) { i.predicate = it.ifBlank { null } }).align(AlignX.FILL) }
        }
    }

    private fun switchPrimary(k: KeyDraft, primary: Boolean) {
        if (primary == k.primary) return
        if (primary) { draft.uniqueKeys.remove(k); draft.primaryKey?.let { old -> old.primary = false; draft.uniqueKeys += old }; draft.primaryKey = k }
        else { draft.primaryKey = null; draft.uniqueKeys += k }
        k.primary = primary
        rebuildTree(k)
    }

    private fun fkForm(f: FkDraft): JComponent {
        val tables = draft.schema.tables.filter { !it.isView }.map { it.name }
        val pairs = PairsModel(f)
        val target = ComboBox((listOf("") + tables).toTypedArray()).apply {
            selectedItem = f.refTable.orEmpty()
            addActionListener { f.refTable = (selectedItem as String).ifBlank { null }; pairs.fireTableDataChanged(); changed() }
        }
        val table = JBTable(pairs).apply {
            columnModel.getColumn(0).cellEditor = DefaultCellEditor(ComboBox(draft.columns.map { it.name }.toTypedArray()))
            columnModel.getColumn(1).cellEditor = object : DefaultCellEditor(JTextField()) {
                override fun getTableCellEditorComponent(t: javax.swing.JTable?, v: Any?, s: Boolean, r: Int, c: Int): java.awt.Component {
                    val cols = f.refTable?.let(draft.schema::table)?.columns?.map { it.name }.orEmpty()
                    return ComboBox((listOf("") + cols).toTypedArray()).apply { selectedItem = v ?: "" }.also { box -> editorComponent = box }
                }
                override fun getCellEditorValue(): Any? = (editorComponent as? ComboBox<*>)?.selectedItem
            }
        }
        val add = object : DumbAwareAction("Add Column Pair", null, AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { f.pairs += null to null; pairs.fireTableDataChanged(); changed() }
        }
        val remove = object : DumbAwareAction("Remove Column Pair", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) { table.selectedRow.takeIf { it >= 0 }?.let { f.pairs.removeAt(it); pairs.fireTableDataChanged(); changed() } }
        }
        val tb = ActionManager.getInstance().createActionToolbar("MyDbFkPairs", DefaultActionGroup(add, remove), true).also { it.targetComponent = table }
        return panel {
            row("Name:") { cell(boundText(f.name) { draft.renameForeignKey(f, it) }).align(AlignX.FILL) }
            row("Target table:") { cell(target).align(AlignX.FILL) }
            row { cell(tb.component) }
            row { cell(JBScrollPane(table).apply { preferredSize = Dimension(JBUI.scale(360), JBUI.scale(120)) }).align(AlignX.FILL) }
        }
    }

    private inner class PairsModel(private val f: FkDraft) : AbstractTableModel() {
        override fun getRowCount() = f.pairs.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Column" else "Target column"
        override fun isCellEditable(row: Int, column: Int) = true
        override fun getValueAt(row: Int, column: Int): Any? = f.pairs[row].let { if (column == 0) it.first else it.second }
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val v = (value as? String)?.ifBlank { null }
            f.pairs[row] = if (column == 0) f.pairs[row].copy(first = v) else f.pairs[row].copy(second = v)
            changed()
        }
    }

    // ---- preview, toolbar ------------------------------------------------------------------------

    private fun refreshPreview() {
        val problems = draft.problems()
        val text = runCatching { draft.preview() }.getOrElse { "-- ${it.message}" }
        ApplicationManager.getApplication().runWriteAction { previewDocument.setText(text) }
        problemsLabel.text = if (problems.isEmpty()) "No problems" else "${problems.size} problem(s): " + problems.joinToString("; ")
        problemsLabel.foreground = if (problems.isEmpty()) JBUI.CurrentTheme.Label.disabledForeground() else com.intellij.ui.JBColor.RED
        isOKActionEnabled = draft.statements().isNotEmpty() || draft.isNew
    }

    /**
     * "+": on the Columns or Foreign Keys folder (or one of their items) adds that kind right away;
     * elsewhere a popup asks what to add.
     */
    private fun addPopup(e: AnActionEvent) {
        if (quickAdd() != null) return
        val choices = linkedMapOf<String, () -> Any>(
            "Column" to { draft.addColumn() },
            "Primary Key" to { draft.addPrimaryKey() },
            "Unique Key" to { draft.addUniqueKey() },
            "Foreign Key" to { draft.addForeignKey() },
            "Index" to { draft.addIndex() },
            "Check" to { draft.addCheck() },
        )
        if (draft.primaryKey != null) choices.remove("Primary Key")
        // on the Keys folder only keys can be added
        if (selected() == Folder.KEYS || selected() is KeyDraft) choices.keys.retainAll(setOf("Primary Key", "Unique Key"))
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>("Add", choices.keys.toList()) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                doFinalStep { val created = choices[selectedValue]!!(); rebuildTree(created); changed() }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }

    /** Choices the "+" popup offers for the current selection (tests). */
    internal fun popupChoices(): List<String> {
        val all = mutableListOf("Column", "Primary Key", "Unique Key", "Foreign Key", "Index", "Check")
        if (draft.primaryKey != null) all.remove("Primary Key")
        if (selected() == Folder.KEYS || selected() is KeyDraft) all.retainAll(setOf("Primary Key", "Unique Key"))
        return all
    }

    /** Adds the kind the selection implies and selects it; null when the choice needs the popup. */
    internal fun quickAdd(): Any? {
        val sel = selected()
        val created = when {
            sel == Folder.COLUMNS || sel is ColumnDraft -> draft.addColumn()
            sel == Folder.FOREIGN_KEYS || sel is FkDraft -> draft.addForeignKey()
            sel == Folder.INDEXES || sel is IndexDraft -> draft.addIndex()
            sel == Folder.CHECKS || sel is CheckDraft -> draft.addCheck()
            sel == Folder.KEYS && draft.primaryKey != null -> draft.addUniqueKey()
            else -> return null
        }
        rebuildTree(created); changed()
        return created
    }

    /** Selects a folder node by its title (tests). */
    internal fun selectFolder(title: String) {
        val root = tree.model.root as DefaultMutableTreeNode
        val node = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.first { (it.userObject as? Folder)?.title == title }
        tree.selectionPath = javax.swing.tree.TreePath(node.path)
    }

    private fun removeSelected() {
        when (val o = selected()) {
            is ColumnDraft -> draft.columns.remove(o)
            is KeyDraft -> if (o.primary) draft.primaryKey = null else draft.uniqueKeys.remove(o)
            is FkDraft -> draft.foreignKeys.remove(o)
            is IndexDraft -> draft.indexes.remove(o)
            is CheckDraft -> draft.checks.remove(o)
            else -> return
        }
        rebuildTree(draft); changed()
    }

    private fun moveSelected(delta: Int) {
        val c = selected() as? ColumnDraft ?: return
        val i = draft.columns.indexOf(c)
        val j = (i + delta).takeIf { it in draft.columns.indices } ?: return
        draft.columns.removeAt(i); draft.columns.add(j, c)
        rebuildTree(c); changed()
    }

    override fun createCenterPanel(): JComponent {
        fun act(text: String, icon: javax.swing.Icon, run: (AnActionEvent) -> Unit) = object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run(e)
        }
        val group = DefaultActionGroup(
            act("Add", AllIcons.General.Add) { addPopup(it) },
            act("Remove", AllIcons.General.Remove) { removeSelected() },
            act("Move Up", AllIcons.Actions.MoveUp) { moveSelected(-1) },
            act("Move Down", AllIcons.Actions.MoveDown) { moveSelected(1) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("MyDbTableDialog", group, true).also { it.targetComponent = tree }
        val left = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        val top = Splitter(false, 0.35f).apply { firstComponent = left; secondComponent = forms }
        val bottom = JPanel(BorderLayout()).apply {
            add(preview.component, BorderLayout.CENTER)
            add(problemsLabel, BorderLayout.SOUTH)
        }
        return Splitter(true, 0.6f).apply {
            firstComponent = top
            secondComponent = bottom
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(620))
        }
    }

    /** Warns before executing an incomplete definition, then runs it. */
    override fun doOKAction() {
        val problems = draft.problems()
        if (problems.isNotEmpty() && !MessageDialogBuilder.yesNo("Problems Found", problems.joinToString("\n") + "\n\nExecute anyway?").ask(contentPane)) return
        val statements = draft.statements()
        isOKActionEnabled = false
        setErrorText(null)
        object : Task.Backgroundable(project, if (draft.isNew) "Creating ${draft.name}" else "Modifying ${draft.name}", true) {
            private var error: String? = null
            override fun run(indicator: ProgressIndicator) { error = ObjectDdl.execute(project, config, database, draft, statements) }
            override fun onSuccess() { if (error == null) close(OK_EXIT_CODE) else { setErrorText(error); isOKActionEnabled = true } }
            override fun onThrowable(t: Throwable) { setErrorText(t.message ?: t.toString()); isOKActionEnabled = true }
        }.queue()
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(preview)
        super.dispose()
    }
}
