package dev.teo.mydb.plugin.refactoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import dev.teo.mydb.plugin.sql.SqlFileType
import dev.teo.mydb.plugin.sql.psi.DbObjectElement
import javax.swing.Action
import javax.swing.JComponent

/** Rename dialog with DDL preview and "execute SQL" option. */
class DbRenameDialog(private val project: Project, private val target: DbObjectElement) : DialogWrapper(project, true) {
    private val name = JBTextField(target.name)
    private val executeDdl = JBCheckBox("Execute SQL on the database", true)
    private val commentsAndStrings = JBCheckBox("Search in comments and strings", false)
    private val textOccurrences = JBCheckBox("Search for text occurrences", false)
    private val scope = com.intellij.openapi.ui.ComboBox(RenameScope.entries.toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.title }
    }
    /** Options chosen in the dialog (tests). */
    val options get() = RenameOptions(commentsAndStrings.isSelected, textOccurrences.isSelected, scope.selectedItem as RenameScope)
    private val document = EditorFactory.getInstance().createDocument(DbRename.ddl(target, target.name))
    private val preview = EditorFactory.getInstance().createViewer(document, project, com.intellij.openapi.editor.EditorKind.PREVIEW)
    private val status = JBLabel()
    private val previewAction = object : DialogWrapperAction("Preview") {
        override fun doAction(e: java.awt.event.ActionEvent?) = showUsages()
    }
    /** Puts the DDL into a new console of the data source instead of running it. */
    private val consoleAction = object : DialogWrapperAction("Open in Console") {
        override fun doAction(e: java.awt.event.ActionEvent?) {
            val config = dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).find(target.dataSourceId) ?: return
            val vf = dev.teo.mydb.plugin.console.ConsoleService.getInstance(project).openConsole(config, newConsole = true)
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf) ?: return
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.setText(DbRename.ddl(target, name.text.trim())) }
            close(CANCEL_EXIT_CODE)
        }
    }

    init {
        title = "Rename"
        (preview as? com.intellij.openapi.editor.ex.EditorEx)?.highlighter =
            com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
        name.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = update()
        })
        init()
        update()
    }

    private fun update() {
        val n = name.text.trim()
        val same = n.isEmpty() || n == target.name
        ApplicationManager.getApplication().runWriteAction { document.setText(if (same) "" else DbRename.ddl(target, n)) }
        status.text = if (same) "Nothing to rename" else ""
        isOKActionEnabled = !same
        previewAction.isEnabled = !same
        consoleAction.isEnabled = !same
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Rename ${target.kind} '${target.name}' and its usages to:") }
        row { cell(name).align(AlignX.FILL).focused() }
        row { cell(executeDdl) }
        row { cell(commentsAndStrings); cell(textOccurrences) }
        row("Scope:") { cell(scope) }
        row { cell(preview.component).align(Align.FILL) }.resizableRow()
        row { cell(status) }
    }.also { it.preferredSize = java.awt.Dimension(560, 320) }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, consoleAction, previewAction, okAction.also { it.putValue(Action.NAME, "Refactor") })

    /** Button texts, left to right (tests). */
    fun buttonTexts(): List<String> = createActions().map { it.getValue(Action.NAME) as String }

    /** Usages in the Find tool window. */
    private fun showUsages() = DbRename.showUsages(project, target, "Refactoring Preview")

    override fun doOKAction() {
        try {
            DbRename.perform(project, target, name.text.trim(), executeDdl.isSelected, options)
            super.doOKAction()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Rename Failed")
        }
    }

    override fun dispose() { EditorFactory.getInstance().releaseEditor(preview); super.dispose() }
}
