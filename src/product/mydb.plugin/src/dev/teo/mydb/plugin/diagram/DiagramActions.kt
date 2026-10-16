package dev.teo.mydb.plugin.diagram

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.model.ModelService

/** Schema or table selected in the explorer, resolved against the current model. */
private fun AnActionEvent.diagramTarget(): Pair<ExplorerNode, String?>? = when (val n = getData(ExplorerDataKeys.SELECTED)?.singleOrNull()) {
    is ExplorerNode.Schema -> n to null
    is ExplorerNode.Table -> n to n.table.name
    else -> null
}

private fun AnActionEvent.diagramFile(): DiagramFile? {
    val project = project ?: return null
    val (node, table) = diagramTarget() ?: return null
    val (database, schemaName) = when (node) {
        is ExplorerNode.Schema -> node.database to node.schema.name
        is ExplorerNode.Table -> node.database to node.schema
        else -> return null
    }
    val schema = ModelService.getInstance(project).model(node.config.id)?.schema(database, schemaName) ?: return null
    return DiagramEditors.erdFile(project, node.config.id, database, schema, table)
}

/** ⌥⇧⌘U: ERD of the selected schema or table in an editor tab. */
class ShowDiagramAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.diagramTarget() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        e.diagramFile()?.let { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(it, true) }
    }
}

/** ⌥⌘U: the same diagram in a popup. */
class ShowDiagramPopupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.diagramTarget() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        e.diagramFile()?.let { DiagramEditors.showPopup(project, it, e.dataContext) }
    }
}
