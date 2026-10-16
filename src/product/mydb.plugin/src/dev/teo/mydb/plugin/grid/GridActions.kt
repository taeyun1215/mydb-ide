package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.GridUtil
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Sends the pending grid changes to the database in one transaction; ⌘↩ in the grid. */
class SubmitGridChangesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    private fun mutator(e: AnActionEvent) = (GridUtil.getDataGrid(e.dataContext)?.dataHookup as? JdbcGridHookUp)?.mutator as? JdbcGridHookUp.Mutator
    override fun update(e: AnActionEvent) {
        val m = mutator(e)
        e.presentation.isVisible = GridUtil.getDataGrid(e.dataContext)?.dataHookup is JdbcGridHookUp
        e.presentation.isEnabled = m?.hasPendingChanges() == true
    }
    override fun actionPerformed(e: AnActionEvent) { mutator(e)?.submit(GridRequestSource(null), true) }
}

/** DDL of the table behind an editable result or table editor. */
class ShowGridDdlAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    private fun target(e: AnActionEvent): Triple<dev.teo.mydb.core.datasource.DataSourceConfig, String, dev.teo.mydb.core.model.DbTable>? {
        val project = e.project ?: return null
        val hookUp = GridUtil.getDataGrid(e.dataContext)?.dataHookup as? JdbcGridHookUp ?: return null
        val t = hookUp.editTarget ?: return null
        val config = hookUp.sessionKey?.let { dev.teo.mydb.plugin.console.ConsoleService.getInstance(project).existing(it) }?.dataSource ?: return null
        val model = dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(config.id) ?: return null
        val database = t.database ?: model.databases.singleOrNull()?.name ?: return null
        val table = model.schema(database, t.schema)?.table(t.table) ?: return null
        return Triple(config, database, table)
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = GridUtil.getDataGrid(e.dataContext)?.dataHookup is JdbcGridHookUp
        e.presentation.isEnabled = target(e) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val (config, database, table) = target(e) ?: return
        val schema = (GridUtil.getDataGrid(e.dataContext)?.dataHookup as JdbcGridHookUp).editTarget!!.schema
        dev.teo.mydb.plugin.explorer.ExplorerActions.openDdl(e.project!!, config, database, schema, table)
    }
}

/** Toolbar above result and table-editor grids: paging, reload, add/delete row, revert, submit, export. */
object GridToolbar {
    private const val WRAPPER = "mydb.grid.wrapper"

    fun component(grid: DataGrid): JComponent {
        val inner = grid.panel.component
        (inner.getClientProperty(WRAPPER) as? JComponent)?.let { return it }
        val am = ActionManager.getInstance()
        val group = DefaultActionGroup().apply {
            (am.getAction("Console.TableResult.NavigationAndEditing.Group") as? ActionGroup)?.let { add(it) }
            add(Separator.getInstance())
            am.getAction("MyDb.PreviewGridChanges")?.let { add(it) }
            am.getAction("MyDb.SubmitGridChanges")?.let { add(it) }
            add(Separator.getInstance())
            // transaction of the grid's own session
            listOf("MyDb.TxMode", "MyDb.Commit", "MyDb.Rollback").forEach { id -> am.getAction(id)?.let { add(it) } }
            add(Separator.getInstance())
            am.getAction("MyDb.GridDdl")?.let { add(it) }
            am.getAction("MyDb.CompareGridData")?.let { add(it) }
            am.getAction("MyDb.ShowChart")?.let { add(it) }
            add(Separator.getInstance())
            am.getAction("MyDb.ExportGrid")?.let { add(it) }
        }
        val toolbar = am.createActionToolbar("MyDbGridToolbar", group, true).also { it.targetComponent = inner }
        am.getAction("MyDb.SubmitGridChanges")?.registerCustomShortcutSet(CustomShortcutSet(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK)), inner, grid)
        val banner = SubmitErrorBanner()
        (grid.dataHookup as? JdbcGridHookUp)?.submitListeners?.add { banner.show(it) }
        val wrapper = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(inner, BorderLayout.CENTER)
            add(banner, BorderLayout.SOUTH)
        }
        inner.putClientProperty(WRAPPER, wrapper)
        return wrapper
    }
}

/**
 * Banner under the grid after a failed submit: `[SQLSTATE] message`, a Details link with the failed
 * statement and the full server message, and a close button. Hidden again on success.
 */
class SubmitErrorBanner : JPanel(BorderLayout()) {
    private val text = com.intellij.ui.components.JBLabel(com.intellij.icons.AllIcons.General.Error)
    private var details = ""
    val shownText: String? get() = if (isVisible) text.text else null

    init {
        isVisible = false
        border = com.intellij.util.ui.JBUI.Borders.compound(
            com.intellij.util.ui.JBUI.Borders.customLineTop(com.intellij.ui.JBColor.border()), com.intellij.util.ui.JBUI.Borders.empty(6, 8))
        background = com.intellij.openapi.ui.MessageType.ERROR.popupBackground
        add(text, BorderLayout.CENTER)
        val actions = JPanel().apply { isOpaque = false }
        actions.add(com.intellij.ui.components.ActionLink("Details") {
            com.intellij.openapi.ui.Messages.showErrorDialog(this, details, "Submit Failed")
        })
        actions.add(com.intellij.ui.InplaceButton(com.intellij.openapi.ui.popup.IconButton("Close", com.intellij.icons.AllIcons.Actions.Close,
            com.intellij.icons.AllIcons.Actions.CloseHovered)) { isVisible = false })
        add(actions, BorderLayout.EAST)
    }

    fun show(result: dev.teo.mydb.core.edit.SubmitResult) {
        when (result) {
            is dev.teo.mydb.core.edit.SubmitResult.Ok -> isVisible = false
            is dev.teo.mydb.core.edit.SubmitResult.Failed -> {
                val firstLine = result.message.lineSequence().first()
                text.text = (result.sqlState?.let { "[$it] " } ?: "") + firstLine
                details = result.message + "\n\n" + result.statement.display
                isVisible = true
            }
        }
        revalidate(); repaint()
    }
}
