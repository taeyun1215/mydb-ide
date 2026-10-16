package dev.teo.mydb.plugin.console

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.SqlFileType
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * "Query history" of a data source: executed statements (most recent last) with a
 * delete button, a highlighted preview, and Paste to insert the statement at the console caret.
 */
class HistoryDialog(private val project: Project, private val config: DataSourceConfig, private val target: Editor?) : DialogWrapper(project, true) {
    private val history = ConsoleService.getInstance(project).history
    val model = CollectionListModel(history.list(config.id))
    private val list = JBList(model).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val document = EditorFactory.getInstance().createDocument("")
    private val preview = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW).also {
        (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
    }

    private val pasteAction = object : DialogWrapperAction("Paste") {
        override fun doAction(e: ActionEvent?) { paste(); close(OK_EXIT_CODE) }
    }

    init {
        title = "${config.name} Query History"
        list.addListSelectionListener { ApplicationManager.getApplication().runWriteAction { document.setText(list.selectedValue.orEmpty()) } }
        init()
        if (model.size > 0) list.selectedIndex = model.size - 1
    }

    /** Inserts the selected statement at the console caret. */
    fun paste() {
        val text = list.selectedValue ?: return
        val editor = target ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.caretModel.offset, text)
            editor.caretModel.moveToOffset(editor.caretModel.offset + text.length)
        }
    }

    fun select(index: Int) { list.selectedIndex = index }

    override fun createCenterPanel(): JComponent {
        val left = ToolbarDecorator.createDecorator(list)
            .disableAddAction().disableUpDownActions()
            .setRemoveAction {
                val i = list.selectedIndex.takeIf { it >= 0 } ?: return@setRemoveAction
                history.remove(config.id, model.getElementAt(i)); model.remove(i)
            }.createPanel()
        return Splitter(false, 0.4f).apply {
            firstComponent = left
            secondComponent = preview.component
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(420))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction.also { it.putValue(Action.NAME, "Close") }, pasteAction)

    override fun dispose() { EditorFactory.getInstance().releaseEditor(preview); super.dispose() }
}

/** Console toolbar: query history of the console's data source. */
class ShowHistoryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.boundFile() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.boundFile() ?: return
        val config = SqlDataSourceBinding.getInstance(project).context(file)?.config ?: return
        HistoryDialog(project, config, e.getData(CommonDataKeys.EDITOR)).show()
    }
}
