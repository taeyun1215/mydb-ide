package dev.teo.mydb.plugin.objects

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.explorer.FolderKind
import dev.teo.mydb.plugin.model.ModelService

private fun AnActionEvent.node() = getData(ExplorerDataKeys.SELECTED)?.singleOrNull()

/** Schema the "New" popup creates objects in: a schema node, a table folder or a table of it. */
private fun AnActionEvent.newTarget(): Pair<ExplorerNode, Pair<String, String>>? = when (val n = node()) {
    is ExplorerNode.Schema -> n to (n.database to n.schema.name)
    is ExplorerNode.Folder -> if (n.kind == FolderKind.TABLES) n to (n.database to n.schema) else null
    is ExplorerNode.Table -> n to (n.database to n.schema)
    else -> null
}

/** ⌘N in the explorer: "New" popup with the kinds that can be created there. */
class NewObjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.newTarget()?.let { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.first.config) } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (node, place) = e.newTarget() ?: return
        val (database, schemaName) = place
        val schema = ModelService.getInstance(project).model(node.config.id)?.schema(database, schemaName) ?: return
        val kinds = newKinds(node.config.dbms)
        val step = object : BaseListPopupStep<String>("New", kinds) {
            override fun isSpeedSearchEnabled() = true
            override fun getIconFor(value: String) = when (value) {
                "Table" -> com.intellij.icons.AllIcons.Nodes.DataTables
                "View", "Materialized View" -> com.intellij.icons.AllIcons.Nodes.DataSchema
                "Sequence" -> com.intellij.icons.AllIcons.Nodes.Enum
                else -> com.intellij.icons.AllIcons.Nodes.Folder
            }
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep { dialog(project, node.config, database, schema, selectedValue).show() }
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }
}

/** Kinds offered by the "New" popup of a schema. */
fun newKinds(dbms: dev.teo.mydb.core.datasource.Dbms = dev.teo.mydb.core.datasource.Dbms.POSTGRES) = when (dbms) {
    dev.teo.mydb.core.datasource.Dbms.POSTGRES -> listOf("Table", "View", "Materialized View", "Sequence", "Schema")
    dev.teo.mydb.core.datasource.Dbms.MYSQL -> listOf("Table", "View", "Schema")
    dev.teo.mydb.core.datasource.Dbms.SQLITE -> listOf("Table", "View")
    dev.teo.mydb.core.datasource.Dbms.DDL -> emptyList()
}

/** The create dialog of one kind of the "New" popup. */
fun dialog(project: com.intellij.openapi.project.Project, config: dev.teo.mydb.core.datasource.DataSourceConfig, database: String,
           schema: dev.teo.mydb.core.model.DbSchema, kind: String): com.intellij.openapi.ui.DialogWrapper = when (kind) {
    "View" -> ViewDialog(project, config, database, dev.teo.mydb.core.ddl.ViewDraft(schema.name, dialect = dev.teo.mydb.core.exec.SqlDialect.of(config.dbms)))
    "Materialized View" -> ViewDialog(project, config, database, dev.teo.mydb.core.ddl.ViewDraft(schema.name, materialized = true))
    "Sequence" -> SequenceDialog(project, config, database, dev.teo.mydb.core.ddl.SequenceDraft(schema.name))
    "Schema" -> SchemaDialog(project, config, database, dev.teo.mydb.core.ddl.SchemaDraft(dialect = dev.teo.mydb.core.exec.SqlDialect.of(config.dbms)))
    else -> TableDialog(project, config, database, TableDraft(schema, null, config.dbms))
}

/** ⌘F6 on a table: the modify dialog. */
class ModifyTableAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = (e.node() as? ExplorerNode.Table)?.let { !it.table.isView && dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.node() as? ExplorerNode.Table ?: return
        // the stored table: virtual foreign keys are not part of its DDL
        val schema = ModelService.getInstance(project).rawModel(n.config.id)?.schema(n.database, n.schema) ?: return
        TableDialog(project, n.config, n.database, TableDraft(schema, schema.table(n.table.name), n.config.dbms)).show()
    }
}
