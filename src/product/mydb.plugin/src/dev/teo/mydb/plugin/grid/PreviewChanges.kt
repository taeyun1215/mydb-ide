package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.GridUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import dev.teo.mydb.plugin.sql.SqlFileType
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

/** "DML Preview": the statements a submit would send, with literal values; Cancel / Submit. */
class PreviewChangesDialog(project: Project, val text: String, private val onSubmit: () -> Unit) : DialogWrapper(project, true) {
    private val viewer = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(text), project, EditorKind.PREVIEW).also {
        (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
    }

    init { title = "DML Preview"; init() }

    override fun createCenterPanel(): JComponent = viewer.component.apply { preferredSize = Dimension(JBUI.scale(720), JBUI.scale(300)) }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, object : DialogWrapperAction("Submit") {
        override fun doAction(e: ActionEvent?) { onSubmit(); close(OK_EXIT_CODE) }
    }.also { it.putValue(DEFAULT_ACTION, true) })

    override fun dispose() { EditorFactory.getInstance().releaseEditor(viewer); super.dispose() }
}

class PreviewGridChangesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    private fun mutator(e: AnActionEvent) = (GridUtil.getDataGrid(e.dataContext)?.dataHookup as? JdbcGridHookUp)?.mutator as? JdbcGridHookUp.Mutator
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = GridUtil.getDataGrid(e.dataContext)?.dataHookup is JdbcGridHookUp
        e.presentation.isEnabled = mutator(e)?.hasPendingChanges() == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val m = mutator(e) ?: return
        val project = e.project ?: return
        PreviewChangesDialog(project, m.pendingChanges) { m.submit(GridRequestSource(null), true) }.show()
    }
}
