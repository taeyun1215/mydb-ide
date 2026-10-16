package dev.teo.mydb.plugin.transfer

import com.intellij.database.datagrid.GridUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import dev.teo.mydb.core.transfer.TableData
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.grid.JdbcGridHookUp

private fun AnActionEvent.explorerNode() = getData(ExplorerDataKeys.SELECTED)?.singleOrNull()

/** Explorer: Import/Export → Export Data to File. Reads the whole table first. */
class ExportTableAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.explorerNode() is ExplorerNode.Table }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val node = e.explorerNode() as? ExplorerNode.Table ?: return
        object : Task.Backgroundable(project, "Reading ${node.schema}.${node.table.name}", true) {
            private var data: TableData? = null
            override fun run(indicator: ProgressIndicator) { data = DataExport.loadTable(project, node.config, node.schema, node.table.name) }
            override fun onSuccess() { data?.let { ExportDialog(project, it).show() } }
            override fun onThrowable(error: Throwable) { Messages.showErrorDialog(project, error.message ?: error.toString(), "Export Data") }
        }.queue()
    }
}

/** Result grid: export the rows shown in the grid. */
class ExportGridAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun hookUp(e: AnActionEvent) = GridUtil.getDataGrid(e.dataContext)?.dataHookup as? JdbcGridHookUp
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = hookUp(e)?.currentPage() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val hookUp = hookUp(e) ?: return
        val page = hookUp.currentPage() ?: return
        ExportDialog(project, DataExport.fromPage(page, page.sourceTable?.let { (s, t) -> "$s.$t" })).show()
    }
}

/** Explorer: Import/Export → Import Data from File, into the selected table or a new table of the schema. */
class ImportDataAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val n = e.explorerNode()
        e.presentation.isEnabledAndVisible = (n is ExplorerNode.Table && !n.table.isView) || n is ExplorerNode.Schema
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val node = e.explorerNode() ?: return
        val descriptor = FileChooserDescriptorFactory.singleFile().withExtensionFilter("CSV", "csv", "tsv", "txt").withTitle("Import Data from File")
        val file = FileChooser.chooseFile(descriptor, project, null)?.toNioPath() ?: return
        when (node) {
            is ExplorerNode.Table -> ImportDialog(project, node.config, node.database, node.schema, file, node.table).show()
            is ExplorerNode.Schema -> ImportDialog(project, node.config, node.database, node.schema.name, file, null).show()
            else -> {}
        }
    }
}
