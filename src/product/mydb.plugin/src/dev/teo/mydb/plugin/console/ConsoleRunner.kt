package dev.teo.mydb.plugin.console

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import dev.teo.mydb.core.exec.*
import dev.teo.mydb.plugin.grid.EditTarget
import dev.teo.mydb.plugin.grid.PageQuery
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlContext
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding

/** Runs statements of a console: target selection, execution, output, results, history. */
object ConsoleRunner {

    /** What ⌘↩ would run: the selection, or candidates around the caret under the "caret inside" setting. */
    fun targets(editor: Editor): List<RunTarget> {
        val sel = editor.selectionModel.takeIf { it.hasSelection() }?.let { it.selectionStart until it.selectionEnd }
        return RunTargets.resolve(editor.document.text, editor.caretModel.offset, sel, dev.teo.mydb.plugin.settings.MyDbSettings.get().caretInside)
    }

    fun run(project: Project, editor: Editor, file: VirtualFile) {
        val ctx = SqlDataSourceBinding.getInstance(project).context(file) ?: return
        val targets = targets(editor)
        when (targets.size) {
            0 -> return
            1 -> execute(project, file, ctx, targets.single())
            else -> choose(project, editor, targets) { execute(project, file, ctx, it) }
        }
    }

    /** "Statement" chooser shown when the caret is between statements. */
    private fun choose(project: Project, editor: Editor, targets: List<RunTarget>, onChosen: (RunTarget) -> Unit) {
        JBPopupFactory.getInstance().createListPopup(chooserStep(project, targets, onChosen)).showInBestPositionFor(editor)
    }

    /** Entry below the targets that opens the Query Execution settings. */
    const val CUSTOMIZE = "Customize…"

    fun chooserStep(project: Project, targets: List<RunTarget>, onChosen: (RunTarget) -> Unit) =
        object : BaseListPopupStep<Any>("Run", targets + CUSTOMIZE) {
            override fun getTextFor(value: Any): String = when (value) {
                is RunTarget.Statement -> value.statement.text.replace(Regex("\\s+"), " ").take(80)
                is RunTarget.Script -> value.statements.joinToString("; ") { it.text.replace(Regex("\\s+"), " ") }.take(80)
                else -> value.toString()
            }
            override fun getSeparatorAbove(value: Any) = if (value == CUSTOMIZE) com.intellij.openapi.ui.popup.ListSeparator() else null
            override fun onChosen(selectedValue: Any, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep {
                    if (selectedValue is RunTarget) onChosen(selectedValue)
                    else com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "Query Execution")
                }
                return FINAL_CHOICE
            }
        }

    fun execute(project: Project, file: VirtualFile, ctx: SqlContext, target: RunTarget) {
        val statements = when (target) {
            is RunTarget.Statement -> listOf(target.statement)
            is RunTarget.Script -> target.statements
        }
        if (!confirmUnsafe(project, statements)) return
        object : Task.Backgroundable(project, "Running ${statements.size} statement(s)", true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = runStatements(project, file, ctx, statements) { indicator.checkCanceled() }
                ApplicationManager.getApplication().invokeLater {
                    outcome?.let { RunMarkers.show(project, file, it.runs) }
                    ResultsToolWindowFactory.show(project) { panel ->
                        when {
                            outcome == null -> {}
                            outcome.results.isEmpty() -> panel.showOutput(outcome.handle)
                            else -> panel.showResults(outcome.handle, outcome.results)
                        }
                    }
                }
            }
        }.queue()
    }

    /** Asks before DELETE / UPDATE without WHERE when the setting is on; true to go on. */
    var confirmHook: ((List<String>) -> Boolean)? = null

    private fun confirmUnsafe(project: Project, statements: List<ScriptStatement>): Boolean {
        if (!dev.teo.mydb.plugin.settings.MyDbSettings.get().confirmUnsafeStatements) return true
        val unsafe = statements.map { it.text }.filter(dev.teo.mydb.core.exec.UnsafeStatements::isUnsafe)
        if (unsafe.isEmpty()) return true
        confirmHook?.let { return it(unsafe) }
        val listed = unsafe.joinToString("\n") { it.replace(Regex("\\s+"), " ").take(120) }
        return com.intellij.openapi.ui.MessageDialogBuilder.yesNo("Run Without WHERE?",
            "These statements change every row of their table:\n\n$listed\n\nRun them anyway?")
            .yesText("Run").noText("Cancel").asWarning().ask(project)
    }

    /** One result set of a run, shown as a tab. */
    data class ResultTab(val title: String, val page: ResultPage, val query: PageQuery, val edit: EditTarget?, val counter: dev.teo.mydb.plugin.grid.RowCounter? = null)

    /** What a run produced; [error] is set when a statement failed and the rest were skipped. */
    data class RunOutcome(val handle: SessionHandle, val results: List<ResultTab>, val executed: Int, val error: String?,
                          val runs: List<StatementRun> = emptyList())

    /** One executed statement: where it is in the console, whether it succeeded, how long it took. */
    data class StatementRun(val statement: ScriptStatement, val ok: Boolean, val millis: Long)

    /**
     * Runs [statements] on the console's session in order, logging each one; stops at the first error
     * Returns null when the connection cannot be opened. Blocking: call off the EDT.
     */
    fun runStatements(project: Project, file: VirtualFile, ctx: SqlContext, statements: List<ScriptStatement>, checkCanceled: () -> Unit = {}): RunOutcome? {
        val consoles = ConsoleService.getInstance(project)
        val log = OutputLog.getInstance(project)
        val title = file.nameWithoutExtension
        val handle = try {
            val existed = consoles.existing(file.url) != null
            consoles.session(file.url, title, ctx.config).also { if (!existed) log.info(ctx.config, title, "Connected") }
        } catch (e: Exception) {
            log.error(title, "Connection failed: ${e.message}")
            return null
        }
        val results = mutableListOf<ResultTab>()
        var tabNo = 1
        var executed = 0
        var error: String? = null
        val runStarted = System.currentTimeMillis()
        val runs = mutableListOf<StatementRun>()
        for (st in statements) {
            checkCanceled()
            log.statement(title, "${ctx.database}.${ctx.schema ?: "public"}", st.text)
            consoles.history.add(ctx.config.id, st.text)
            val started = System.currentTimeMillis()
            executed++
            val r = handle.session.execute(st.text)
            runs += StatementRun(st, r !is StatementResult.Error, System.currentTimeMillis() - started)
            when (r) {
                is StatementResult.Rows -> {
                    log.rows(title, r.page.rows.size, 0, System.currentTimeMillis() - started, r.page.executionMillis, r.page.fetchMillis)
                    val source = r.page.sourceTable
                    val tab = source?.let { (s, t) -> "${ctx.database}.$s.$t" } ?: "Result ${tabNo++}"
                    results += ResultTab(tab, r.page, rerun(st.text), source?.let { editTarget(project, ctx, it.first, it.second) }, counter(st.text))
                }
                is StatementResult.Update -> log.updated(title, r.count, r.millis)
                is StatementResult.Error -> {
                    error = "[${r.sqlState ?: ""}] ${r.message}"
                    log.error(title, error)
                    break
                }
            }
        }
        handle.lastMillis = System.currentTimeMillis() - runStarted
        return RunOutcome(handle, results, executed, error, runs)
    }

    /** Row count of a query result: the statement wrapped in `select count(*)` (queries only). */
    fun counter(sql: String): dev.teo.mydb.plugin.grid.RowCounter? {
        val body = sql.trim().trimEnd(';').trim()
        if (!Regex("^(select|with|values|table)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) return null
        return dev.teo.mydb.plugin.grid.RowCounter { session ->
            when (val r = session.execute("select count(*) from ($body) as q", 1)) {
                is StatementResult.Rows -> (r.page.rows.single()[0] as Number).toLong()
                is StatementResult.Error -> throw java.sql.SQLException(r.message, r.sqlState)
                else -> throw java.sql.SQLException("no row count")
            }
        }
    }

    /** Next pages of a console result: the statement is run again and the leading rows are skipped. */
    private fun rerun(sql: String) = PageQuery { session, offset, limit ->
        val r = session.execute(sql, maxRows = offset + limit)
        val page = (r as? StatementResult.Rows)?.page ?: throw java.sql.SQLException((r as? StatementResult.Error)?.message ?: "no rows")
        page.copy(rows = page.rows.drop(offset))
    }

    /** Editable target with key columns from the model. */
    fun editTarget(project: Project, ctx: SqlContext, schema: String, table: String): EditTarget {
        val t = ModelService.getInstance(project).model(ctx.config.id)?.schema(ctx.database, schema)?.table(table)
        return EditTarget(schema, table, t?.primaryKey?.columns ?: emptyList(), t?.columns?.filter { it.default != null || it.identity != null }?.map { it.name }?.toSet() ?: emptySet(),
            ctx.database)
    }
}
