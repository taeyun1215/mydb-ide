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

    /** Spinner over the grid while a page is being fetched; the previous grid stays underneath. */
    private val body = com.intellij.ui.components.JBLoadingPanel(BorderLayout()) { content -> LoadingOverlay(content, this) }
    /** Last load failure, shown under the filter bar until the next load. */
    private val errorLabel = JBLabel(AllIcons.General.Error).apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = com.intellij.ui.JBColor.RED
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        isVisible = false
    }
    /** Small spinner next to the filter fields, where the eye is after pressing Enter. */
    private val busy = com.intellij.util.ui.AsyncProcessIcon("mydb-table-load").apply {
        border = JBUI.Borders.emptyLeft(6)
        isVisible = false
        Disposer.register(this@TableDataEditor, this)
    }
    private var generation = 0
    private var ticker: javax.swing.Timer? = null
    /** A load is in flight; the spinner itself appears only after a short delay, so fast queries don't flicker. */
    internal var isLoading = false
        private set
    internal val spinnerVisible get() = body.isLoading
    internal val error: String? get() = errorLabel.text.takeIf { errorLabel.isVisible }

    init {
        val bar = JPanel(GridLayout(1, 2, JBUI.scale(8), 0))
        bar.add(JPanel(BorderLayout()).apply { add(JBLabel(AllIcons.General.Filter), BorderLayout.WEST); add(where) })
        bar.add(JPanel(BorderLayout()).apply { add(JBLabel(AllIcons.ObjectBrowser.Sorted), BorderLayout.WEST); add(orderBy) })
        bar.border = JBUI.Borders.empty(2, 4)
        val filters = JPanel(BorderLayout()).apply { add(bar); add(busy, BorderLayout.EAST) }
        panel.add(JPanel(BorderLayout()).apply { add(filters, BorderLayout.NORTH); add(errorLabel, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        panel.add(body, BorderLayout.CENTER)
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

    /** Shows the spinner with the elapsed time; called on the EDT. */
    private fun startLoading() {
        val started = System.currentTimeMillis()
        isLoading = true
        errorLabel.isVisible = false
        body.setLoadingText("Running query…")
        body.startLoading()
        busy.isVisible = true; busy.resume()
        ticker?.stop()
        ticker = javax.swing.Timer(1000) {
            body.setLoadingText("Running query… ${(System.currentTimeMillis() - started) / 1000}s")
        }.apply { start() }
    }

    private fun stopLoading() {
        isLoading = false
        ticker?.stop(); ticker = null
        busy.suspend(); busy.isVisible = false
        body.stopLoading()
    }

    internal fun load() {
        val config = DataSourceStorage.getInstance(project).find(file.dataSourceId) ?: return
        val current = ++generation
        startLoading()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val existed = ConsoleService.getInstance(project).existing(file.sessionKey) != null
                val h = ConsoleService.getInstance(project).session(file.sessionKey, file.table, config, SessionPurpose.TABLE_EDITOR)
                if (!existed) OutputLog.getInstance(project).info(config, file.table, "Connected")
                h to fetch(h.session, 0, h.session.pageSize)
            }
            ApplicationManager.getApplication().invokeLater {
                // A newer Enter superseded this load: its result would overwrite the newer filter.
                if (current != generation) return@invokeLater
                stopLoading()
                result.onSuccess { (h, page) ->
                    val ctx = SqlDataSourceBinding.getInstance(project).context(file) ?: dev.teo.mydb.plugin.sql.SqlContext(config, null, file.database, file.schema)
                    val edit = ConsoleRunner.editTarget(project, ctx.copy(database = file.database), file.schema, file.table)
                    grid?.let { body.contentPanel.remove(dev.teo.mydb.plugin.grid.GridToolbar.component(it)); Disposer.dispose(it) }
                    val g = dev.teo.mydb.plugin.grid.GridFactory.create(project, h.session, page, query(), edit, this, counter(),
                        OutputLog.getInstance(project).gridLog(h.title), h.key)
                    grid = g
                    body.add(dev.teo.mydb.plugin.grid.GridToolbar.component(g), BorderLayout.CENTER)
                    panel.revalidate()
                }.onFailure {
                    val message = it.message ?: it.toString()
                    OutputLog.getInstance(project).error(file.table, message)
                    errorLabel.text = message.lineSequence().first()
                    errorLabel.toolTipText = message
                    errorLabel.isVisible = true
                    panel.revalidate()
                }
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
    override fun dispose() { ticker?.stop(); grid?.let(Disposer::dispose) }
}

/** Loading layer that dims the grid and shows the spinner and text on a card, so it reads over busy data. */
private class LoadingOverlay(content: JPanel, parent: com.intellij.openapi.Disposable) :
    com.intellij.openapi.ui.LoadingDecorator(content, parent, 150) {
    init {
        @Suppress("UseJBColor")
        overlayBackground = com.intellij.ui.JBColor(java.awt.Color(255, 255, 255, 150), java.awt.Color(20, 20, 22, 170))
    }

    override fun customizeLoadingLayer(parent: JPanel, text: javax.swing.JLabel, icon: com.intellij.util.ui.AnimatedIcon):
        com.intellij.ui.components.panels.NonOpaquePanel {
        parent.layout = java.awt.GridBagLayout()
        text.font = JBUI.Fonts.label().biggerOn(2f).asBold()
        text.foreground = com.intellij.util.ui.UIUtil.getLabelForeground()
        val card = object : JPanel(com.intellij.ui.components.panels.VerticalLayout(8, javax.swing.SwingConstants.CENTER)) {
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(16)
                    g2.color = com.intellij.ui.JBColor.PanelBackground
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    g2.color = com.intellij.ui.JBColor.border()
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally { g2.dispose() }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(18, 28)
            add(icon); add(text)
        }
        return com.intellij.ui.components.panels.NonOpaquePanel(card).also { parent.add(it) }
    }
}
