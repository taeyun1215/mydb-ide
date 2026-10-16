package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridUtil
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.teo.mydb.core.datadiff.DataDiff
import dev.teo.mydb.core.transfer.TableData
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.transfer.DataExport

/** Data compare: two result sets as sorted text side by side, with a row summary in the title. */
object DataCompare {
    fun request(leftTitle: String, left: TableData, rightTitle: String, right: TableData, key: List<String>): SimpleDiffRequest {
        val summary = DataDiff.compare(left, right, key).summary()
        val f = DiffContentFactory.getInstance()
        return SimpleDiffRequest("Data: $leftTitle vs $rightTitle — $summary",
            f.create(DataDiff.text(left, key)), f.create(DataDiff.text(right, key)), leftTitle, rightTitle)
    }

    fun show(project: Project, leftTitle: String, left: TableData, rightTitle: String, right: TableData, key: List<String>) =
        DiffManager.getInstance().showDiff(project, request(leftTitle, left, rightTitle, right, key))

    /** Grids open in the IDE: result tabs of the Services tool window and table editors. */
    fun openGrids(project: Project): List<Pair<String, DataGrid>> {
        val results = dev.teo.mydb.plugin.console.ResultsToolWindowFactory.panel(project)?.allGrids().orEmpty()
        val editors = FileEditorManager.getInstance(project).allEditors.filterIsInstance<dev.teo.mydb.plugin.table.TableDataEditor>()
            .mapNotNull { e -> e.grid?.let { (e as com.intellij.openapi.fileEditor.FileEditor).file.name to it } }
        return results + editors
    }

    fun data(grid: DataGrid, title: String): TableData? = (grid.dataHookup as? JdbcGridHookUp)?.currentPage()?.let { DataExport.fromPage(it, title) }
    fun key(grid: DataGrid): List<String> = (grid.dataHookup as? JdbcGridHookUp)?.editTarget?.keyColumns.orEmpty()
}

/** Grid toolbar: compare the rows of this grid with another open result or table editor. */
class CompareGridDataAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val grid = GridUtil.getDataGrid(e.dataContext)
        e.presentation.isVisible = grid?.dataHookup is JdbcGridHookUp
        e.presentation.isEnabled = grid != null && e.project?.let { p -> DataCompare.openGrids(p).any { it.second !== grid } } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val grid = GridUtil.getDataGrid(e.dataContext) ?: return
        val others = DataCompare.openGrids(project).filter { it.second !== grid }
        val mine = DataCompare.openGrids(project).firstOrNull { it.second === grid }?.first ?: "this result"
        val step = object : BaseListPopupStep<Pair<String, DataGrid>>("Compare With", others) {
            override fun getTextFor(value: Pair<String, DataGrid>) = value.first
            override fun onChosen(selectedValue: Pair<String, DataGrid>, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep {
                    val left = DataCompare.data(grid, mine) ?: return@doFinalStep
                    val right = DataCompare.data(selectedValue.second, selectedValue.first) ?: return@doFinalStep
                    DataCompare.show(project, mine, left, selectedValue.first, right, DataCompare.key(grid))
                }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }
}

/** Explorer: two tables selected → compare their data (whole tables, matched by the first table's key). */
class CompareTableDataAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun tables(e: AnActionEvent) = e.getData(ExplorerDataKeys.SELECTED).orEmpty().filterIsInstance<ExplorerNode.Table>().takeIf { it.size == 2 }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = tables(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (a, b) = tables(e) ?: return
        object : Task.Backgroundable(project, "Reading ${a.table.name} and ${b.table.name}", true) {
            private var data: Pair<TableData, TableData>? = null
            override fun run(indicator: ProgressIndicator) {
                data = DataExport.loadTable(project, a.config, a.schema, a.table.name) to DataExport.loadTable(project, b.config, b.schema, b.table.name)
            }
            override fun onSuccess() {
                val (l, r) = data ?: return
                DataCompare.show(project, "${a.schema}.${a.table.name}", l, "${b.schema}.${b.table.name}", r, a.table.primaryKey?.columns.orEmpty())
            }
            override fun onThrowable(error: Throwable) { Messages.showErrorDialog(project, error.message ?: error.toString(), "Compare Data") }
        }.queue()
    }
}
