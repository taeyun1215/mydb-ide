package dev.teo.mydb.plugin.console

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.vfs.VirtualFile
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Query consoles live in the IDE config directory, outside any project; they are always editable. */
class ConsoleWritingAccess : NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile): Boolean {
        val consoles = PathManager.getConfigDir().resolve("mydb/consoles").toString()
        return file.isInLocalFileSystem && file.path.startsWith(consoles)
    }
}

/**
 * ⌘↩ is also "Split Line" in the macOS keymap; in an SQL file bound to a data source the
 * Execute action wins.
 */
class ExecutePromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val execute = ActionManager.getInstance().getAction("MyDb.Execute") ?: return null
        if (execute !in actions) return null
        val project = CommonDataKeys.PROJECT.getData(context) ?: return null
        val file = CommonDataKeys.VIRTUAL_FILE.getData(context) ?: return null
        if (CommonDataKeys.EDITOR.getData(context) == null || SqlDataSourceBinding.getInstance(project).dataSourceId(file) == null) return null
        return listOf(execute)
    }
}

/**
 * Generated DDL tabs are read-only, which would switch on Reader Mode and re-render them through the
 * formatter; DDL is shown exactly as generated.
 */
class SqlReaderModeMatcher : com.intellij.codeInsight.actions.ReaderModeMatcher {
    override fun matches(project: com.intellij.openapi.project.Project, file: VirtualFile, editor: com.intellij.openapi.editor.Editor?,
                         mode: com.intellij.codeInsight.actions.ReaderModeProvider.ReaderMode): Boolean? =
        if (file.fileType == dev.teo.mydb.plugin.sql.SqlFileType) false else null
}
