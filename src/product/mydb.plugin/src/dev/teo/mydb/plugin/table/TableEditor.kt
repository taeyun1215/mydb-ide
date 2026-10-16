package dev.teo.mydb.plugin.table

import com.intellij.database.datagrid.DataGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.TableQuery
import dev.teo.mydb.plugin.console.ConsoleRunner
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.console.OutputLog
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.grid.PageQuery
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import java.awt.BorderLayout
import java.awt.GridLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/** Virtual file representing "data of table X". */
class TableDataFile(val dataSourceId: String, val database: String, val schema: String, val table: String) :
    LightVirtualFile(table) {
    override fun equals(other: Any?) = other is TableDataFile && other.dataSourceId == dataSourceId && other.schema == schema && other.table == table
    override fun hashCode() = listOf(dataSourceId, schema, table).hashCode()
    val sessionKey get() = "table:$dataSourceId/$schema.$table"
}

class TableEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is TableDataFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TableDataEditor(project, file as TableDataFile)
    override fun getEditorTypeId() = "mydb-table-data"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * Table data editor: WHERE / ORDER BY bar above the grid; uses its own session.
 */
class TableDataEditor(private val project: Project, private val file: TableDataFile) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())
    /** Column names of the table (from the model) and filter keywords, completed while typing. */
    internal fun completionVariants(keywords: List<String>): List<String> {
        val config = DataSourceStorage.getInstance(project).find(file.dataSourceId)
        val table = config?.let { dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(it.id) }
            ?.databases?.firstNotNullOfOrNull { it.schema(file.schema)?.table(file.table) }
        return table?.columns?.map { it.name }.orEmpty() + keywords
    }
    val where = filterField("WHERE", listOf("and", "or", "not", "like", "ilike", "in", "between", "is null", "is not null", "exists"))
    val orderBy = filterField("ORDER BY", listOf("asc", "desc", "nulls first", "nulls last"))

    private fun filterField(placeholder: String, keywords: List<String>) =
        com.intellij.ui.TextFieldWithAutoCompletion(project,
            com.intellij.ui.TextFieldWithAutoCompletion.StringsCompletionProvider(completionVariants(keywords), AllIcons.Nodes.DataColumn), false, "").apply {
            setPlaceholder(placeholder)
            setShowPlaceholderWhenFocused(true)
        }

    /** Enter reloads, unless the completion popup is open (then Enter picks the item). */
    private fun onEnter(field: com.intellij.ui.EditorTextField, run: () -> Unit) {
        object : com.intellij.openapi.project.DumbAwareAction() {
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                e.presentation.isEnabled = field.editor?.let { com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(it) } == null
            }
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = run()
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke("ENTER")), field, this)
    }
    /** The data grid once the first page has loaded. */
    var grid: DataGrid? = null
        private set

    init {
        val bar = JPanel(GridLayout(1, 2, JBUI.scale(8), 0))
        bar.add(JPanel(BorderLayout()).apply { add(JBLabel(AllIcons.General.Filter), BorderLayout.WEST); add(where) })
        bar.add(JPanel(BorderLayout()).apply { add(JBLabel(AllIcons.ObjectBrowser.Sorted), BorderLayout.WEST); add(orderBy) })
        bar.border = JBUI.Borders.empty(2, 4)
        panel.add(bar, BorderLayout.NORTH)
        val reload = { load() }
        onEnter(where, reload)
        onEnter(orderBy, reload)
        load()
    }

    private fun dialect() = DataSourceStorage.getInstance(project).find(file.dataSourceId)?.let { dev.teo.mydb.core.exec.SqlDialect.of(it.dbms) }
        ?: dev.teo.mydb.core.exec.SqlDialect.POSTGRES

    private fun query() = PageQuery { session, offset, limit -> fetch(session, offset, limit) }

    /** `SELECT count(*)` with the current WHERE filter. */
    private fun counter() = dev.teo.mydb.plugin.grid.RowCounter { session ->
        val sql = "SELECT count(*) FROM ${dialect().qualified(file.schema, file.table)}" +
            where.text.trim().takeIf { it.isNotEmpty() }?.let { " WHERE $it" }.orEmpty()
        when (val r = session.execute(sql, 1)) {
            is dev.teo.mydb.core.exec.StatementResult.Rows -> (r.page.rows.single()[0] as Number).toLong()
            is dev.teo.mydb.core.exec.StatementResult.Error -> throw java.sql.SQLException(r.message, r.sqlState)
            else -> throw java.sql.SQLException("no row count")
        }
    }

    private fun fetch(session: Session, offset: Int, limit: Int): ResultPage {
        val sql = TableQuery.select(file.schema, file.table, where.text, orderBy.text, limit, offset, dialect())
        OutputLog.getInstance(project).statement(file.table, "${file.database}.${file.schema}", sql.replace('\n', ' '))
        val started = System.currentTimeMillis()
        val r = session.execute(sql, limit)
        val page = (r as? dev.teo.mydb.core.exec.StatementResult.Rows)?.page
            ?: throw java.sql.SQLException((r as? dev.teo.mydb.core.exec.StatementResult.Error)?.message)
        OutputLog.getInstance(project).rows(file.table, page.rows.size, offset, System.currentTimeMillis() - started, page.executionMillis, page.fetchMillis)
        return page
    }

    internal fun load() {
        val config = DataSourceStorage.getInstance(project).find(file.dataSourceId) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val existed = ConsoleService.getInstance(project).existing(file.sessionKey) != null
                val h = ConsoleService.getInstance(project).session(file.sessionKey, file.table, config, SessionPurpose.TABLE_EDITOR)
                if (!existed) OutputLog.getInstance(project).info(config, file.table, "Connected")
                h to fetch(h.session, 0, h.session.pageSize)
            }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { (h, page) ->
                    val ctx = SqlDataSourceBinding.getInstance(project).context(file) ?: dev.teo.mydb.plugin.sql.SqlContext(config, null, file.database, file.schema)
                    val edit = ConsoleRunner.editTarget(project, ctx.copy(database = file.database), file.schema, file.table)
                    grid?.let { panel.remove(dev.teo.mydb.plugin.grid.GridToolbar.component(it)); Disposer.dispose(it) }
                    val g = dev.teo.mydb.plugin.grid.GridFactory.create(project, h.session, page, query(), edit, this, counter(),
                        OutputLog.getInstance(project).gridLog(h.title), h.key)
                    grid = g
                    panel.add(dev.teo.mydb.plugin.grid.GridToolbar.component(g), BorderLayout.CENTER)
                    panel.revalidate()
                }.onFailure { OutputLog.getInstance(project).error(file.table, it.message ?: it.toString()) }
            }
        }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = grid?.preferredFocusedComponent
    override fun getName() = "Data"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() { grid?.let(Disposer::dispose) }
}
