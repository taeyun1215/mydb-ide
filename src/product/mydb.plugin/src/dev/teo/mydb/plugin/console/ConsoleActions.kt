package dev.teo.mydb.plugin.console

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.teo.mydb.core.exec.RunTarget
import dev.teo.mydb.core.exec.RunTargets
import dev.teo.mydb.core.plan.QueryPlans
import dev.teo.mydb.plugin.datasource.DataSourceDialog
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Session key and data source the Tx actions act on: the focused result grid's session, else the console in the editor. */
internal fun AnActionEvent.txTarget(): Pair<String, dev.teo.mydb.core.datasource.DataSourceConfig>? {
    val project = project ?: return null
    gridSessionKey()?.let { key -> ConsoleService.getInstance(project).existing(key)?.let { return key to it.dataSource } }
    val file = boundFile() ?: return null
    val config = SqlDataSourceBinding.getInstance(project).context(file)?.config ?: return null
    return file.url to config
}

internal fun AnActionEvent.gridSessionKey(): String? =
    (com.intellij.database.datagrid.GridUtil.getDataGrid(dataContext)?.dataHookup as? dev.teo.mydb.plugin.grid.JdbcGridHookUp)?.sessionKey

internal fun AnActionEvent.consoleTx(): TxSettings? {
    val (key, config) = txTarget() ?: return null
    return ConsoleService.getInstance(project!!).txSettings(key, config)
}

internal fun AnActionEvent.boundFile() = getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { f ->
    project?.let { SqlDataSourceBinding.getInstance(it).dataSourceId(f) } != null
}

/** ⌘↩: run the statement at the caret, the selection, or a chosen target. */
class ExecuteAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null && e.boundFile() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ConsoleRunner.run(project, e.getData(CommonDataKeys.EDITOR) ?: return, e.boundFile() ?: return)
    }
}

/** Explain plan / explain analyze chooser. */
class ExplainAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null && e.boundFile() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.boundFile() ?: return
        val ctx = SqlDataSourceBinding.getInstance(project).context(file) ?: return
        val target = RunTargets.resolve(editor.document.text, editor.caretModel.offset).firstOrNull() as? RunTarget.Statement ?: return
        val step = object : BaseListPopupStep<Boolean>(null, listOf(false, true)) {
            override fun getTextFor(value: Boolean) = if (value) "Explain Analyze" else "Explain Plan"
            override fun onChosen(selectedValue: Boolean, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { explain(project, file, ctx, target.statement.text, selectedValue) }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
    }

    fun explain(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile, ctx: dev.teo.mydb.plugin.sql.SqlContext, sql: String, analyze: Boolean) {
        object : Task.Backgroundable(project, "Explain", true) {
            override fun run(indicator: ProgressIndicator) {
                val h = ConsoleService.getInstance(project).session(file.url, file.nameWithoutExtension, ctx.config)
                val plan = runCatching { QueryPlans.explain(h.session, sql, analyze) }
                ApplicationManager.getApplication().invokeLater {
                    // the plan tab's re-run button explains the same statement again
                    plan.onSuccess { p -> ResultsToolWindowFactory.show(project) { it.showPlan(h, p) { explain(project, file, ctx, sql, analyze) } } }
                        .onFailure { OutputLog.getInstance(project).error(h.title, it.message ?: it.toString()) }
                }
            }
        }.queue()
    }
}

/** ⌘; — Data Sources dialog. */
class DataSourcesDialogAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val current = e.boundFile()?.let { SqlDataSourceBinding.getInstance(project).dataSourceId(it) }
        DataSourceDialog(project, current).show()
    }
}

/** Commit / rollback of a console: shown in manual mode, enabled while a transaction is open. */
abstract class TxAction(private val commit: Boolean) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    /** The session selected in the Services tree, else the session of the console in the editor. */
    private fun handle(e: AnActionEvent) = e.getData(ResultsPanel.SELECTED_SESSION)
        ?: e.project?.let { p -> e.txTarget()?.let { ConsoleService.getInstance(p).existing(it.first) } }
    override fun update(e: AnActionEvent) {
        // the Services tool window always shows both buttons; the console toolbar only in manual mode
        val inServices = e.place == ResultsPanel.TOOLBAR_PLACE
        e.presentation.isVisible = inServices || e.consoleTx()?.mode == dev.teo.mydb.core.datasource.TxMode.MANUAL
        e.presentation.isEnabled = handle(e)?.session?.transactionOpen == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val h = handle(e) ?: return
        ApplicationManager.getApplication().executeOnPooledThread { if (commit) h.session.commit() else h.session.rollback() }
    }
}
class CommitAction : TxAction(true)
class RollbackAction : TxAction(false)
