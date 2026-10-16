package dev.teo.mydb.plugin.console

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import java.awt.BorderLayout
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JPanel

/** Toolbar above consoles and SQL files bound to a data source: run, explain, Tx mode, commit/rollback. */
class ConsoleToolbarProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (SqlDataSourceBinding.getInstance(project).dataSourceId(file) == null) return null
        return Function { fileEditor ->
            val group = ActionManager.getInstance().getAction(GROUP_ID) as? ActionGroup ?: return@Function null
            val toolbar = ActionManager.getInstance().createActionToolbar(PLACE, group, true)
            toolbar.targetComponent = (fileEditor as? TextEditor)?.editor?.contentComponent ?: fileEditor.component
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Editor.BORDER_COLOR)
                add(toolbar.component, BorderLayout.WEST)
            }
        }
    }

    companion object {
        const val GROUP_ID = "MyDb.Editor.Toolbar"
        const val PLACE = "MyDbConsoleToolbar"
    }
}
