package dev.teo.mydb.plugin.explorer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.datasource.DataSourceDialog
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.SqlFileType
import dev.teo.mydb.plugin.table.TableDataFile
import java.awt.datatransfer.StringSelection

internal fun AnActionEvent.nodes(): List<ExplorerNode> = getData(ExplorerDataKeys.SELECTED).orEmpty()

abstract class ExplorerAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

object ExplorerActions {
    /** Opens the table editor. */
    fun openTable(project: Project, n: ExplorerNode.Table) {
        FileEditorManager.getInstance(project).openFile(TableDataFile(n.config.id, n.database, n.schema, n.table.name), true)
    }

    /** Generated, read-only DDL of a table or view. */
    fun openDdl(project: Project, config: dev.teo.mydb.core.datasource.DataSourceConfig, database: String, schema: String, table: dev.teo.mydb.core.model.DbTable) {
        val text = dev.teo.mydb.plugin.util.Dialects.ddl(project, config, database, schema, table)
        val file = LightVirtualFile(table.name, SqlFileType, text).apply { isWritable = false }
        SqlDataSourceBinding.getInstance(project).bind(file, config.id, schema)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun ddlText(project: Project, n: ExplorerNode.Table): String = dev.teo.mydb.plugin.util.Dialects.ddl(project, n.config, n.database, n.schema, n.table)
}

/** Explorer "+": the DBMS list first, then the data source dialog with a new draft. */
class AddDataSourceAction : ExplorerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<dev.teo.mydb.core.datasource.Dbms>("Data Source", supportedDbms()) {
            override fun getTextFor(value: dev.teo.mydb.core.datasource.Dbms) = value.displayName
            override fun getIconFor(value: dev.teo.mydb.core.datasource.Dbms) = dev.teo.mydb.plugin.util.Dialects.icon(value)
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: dev.teo.mydb.core.datasource.Dbms, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                doFinalStep { DataSourceDialog(project, null, selectedValue).show() }
                return FINAL_CHOICE
            }
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }

    companion object { fun supportedDbms() = dev.teo.mydb.plugin.util.Dialects.supported }
}

/**
 * The `shown/total` badge of a data source or database: a checklist of the database's schemas; the checked
 * ones are introspected.
 */
class ChooseSchemasAction : ExplorerAction() {
    private fun target(e: AnActionEvent): Pair<dev.teo.mydb.core.datasource.DataSourceConfig, dev.teo.mydb.core.model.DbDatabase>? {
        val project = e.project ?: return null
        return when (val n = e.nodes().singleOrNull()) {
            is ExplorerNode.Database -> n.config to n.db
            is ExplorerNode.DataSource -> dev.teo.mydb.plugin.model.ModelService.getInstance(project).model(n.config.id)?.let { m ->
                (m.databases.firstOrNull { it.isDefault } ?: m.databases.firstOrNull())?.let { n.config to it } }
            else -> null
        }
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = target(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (config, db) = target(e) ?: return
        val choices = schemaChoices(config, db)
        val list = com.intellij.ui.CheckBoxList<String>().apply { choices.forEach { (name, on) -> addItem(name, name, on) } }
        val panel = com.intellij.ui.components.JBScrollPane(list).apply { preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(260), com.intellij.util.ui.JBUI.scale(260)) }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(panel, list)
            .setTitle("Schemas of ${db.name}").setRequestFocus(true).setResizable(true)
            .addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
                override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                    val chosen = (0 until list.itemsCount).mapNotNull { i -> list.getItemAt(i)?.takeIf { list.isItemSelected(i) } }
                    applySchemas(project, config, db.name, chosen)
                }
            })
            .createPopup().showInBestPositionFor(e.dataContext)
    }

    companion object {
        /** User schemas of the database with whether each is introspected. */
        fun schemaChoices(config: dev.teo.mydb.core.datasource.DataSourceConfig, db: dev.teo.mydb.core.model.DbDatabase): List<Pair<String, Boolean>> {
            val shown = db.schemas.map { it.name }.toSet()
            val system = setOf("information_schema", "mysql", "performance_schema", "sys")
            return db.allSchemaNames.filterNot { it.startsWith("pg_") || it in system }.map { it to (it in shown) }
        }

        /** Saves the new scope and introspects again when it changed. */
        fun applySchemas(project: com.intellij.openapi.project.Project, config: dev.teo.mydb.core.datasource.DataSourceConfig, database: String, schemas: List<String>) {
            val scope = config.introspectionScope
            val others = scope.schemas.filter { it.database != database }
            val next = scope.copy(schemas = (others + schemas.map { dev.teo.mydb.core.datasource.SchemaRef(database, it) }).toSet())
            if (next == scope) return
            val updated = config.copy(introspectionScope = next)
            dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).save(updated)
            dev.teo.mydb.plugin.model.ModelService.getInstance(project).refresh(updated)
        }
    }
}

class DataSourcePropertiesAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DataSourceDialog(project, e.nodes().firstOrNull()?.config?.id).show()
    }
}

class RefreshAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().isNotEmpty() }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.nodes().firstOrNull() ?: return
        // refresh covers the selected node only
        val target = when (n) {
            is ExplorerNode.DataSource, is ExplorerNode.Database -> RefreshTarget.All
            is ExplorerNode.Schema -> RefreshTarget.Schema(n.database, n.schema.name)
            is ExplorerNode.Folder -> if (n.table != null) RefreshTarget.Tables(n.database, n.schema, setOf(n.table)) else RefreshTarget.Schema(n.database, n.schema)
            is ExplorerNode.Table -> RefreshTarget.Tables(n.database, n.schema, setOf(n.table.name))
            is ExplorerNode.Column -> RefreshTarget.Tables(n.database, n.schema, setOf(n.table.name))
            else -> RefreshTarget.All
        }
        ModelService.getInstance(project).refresh(n.config, target)
    }
}

class OpenConsoleAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().isNotEmpty() && e.nodes().all { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) } }
    override fun actionPerformed(e: AnActionEvent) {
        val n = e.nodes().firstOrNull() ?: return
        ConsoleService.getInstance(e.project ?: return).openConsole(n.config, newConsole = false)
    }
}

class NewConsoleAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().isNotEmpty() && e.nodes().all { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) } }
    override fun actionPerformed(e: AnActionEvent) {
        val n = e.nodes().firstOrNull() ?: return
        ConsoleService.getInstance(e.project ?: return).openConsole(n.config, newConsole = true)
    }
}

class EditDataAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = (e.nodes().singleOrNull() as? ExplorerNode.Table)?.let { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val n = e.nodes().singleOrNull() as? ExplorerNode.Table ?: return
        ExplorerActions.openTable(e.project ?: return, n)
    }
}

class GoToDdlAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().singleOrNull() is ExplorerNode.Table }
    override fun actionPerformed(e: AnActionEvent) {
        val n = e.nodes().singleOrNull() as? ExplorerNode.Table ?: return
        ExplorerActions.openDdl(e.project ?: return, n.config, n.database, n.schema, n.table)
    }
}

class CopyDdlAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().any { it is ExplorerNode.Table } }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = e.nodes().filterIsInstance<ExplorerNode.Table>().joinToString("\n\n") { ExplorerActions.ddlText(project, it) }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}

class DdlToConsoleAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().any { it is ExplorerNode.Table } }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tables = e.nodes().filterIsInstance<ExplorerNode.Table>()
        val text = tables.joinToString("\n\n") { ExplorerActions.ddlText(project, it) }
        val vf = ConsoleService.getInstance(project).openConsole(tables.first().config, newConsole = true)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.setText(text)
        }
    }
}

class DisconnectAction : ExplorerAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val ids = e.nodes().map { it.config.id }.toSet()
        e.presentation.isEnabled = project != null && ConsoleService.getInstance(project).all().any { it.dataSource.id in ids }
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ids = e.nodes().map { it.config.id }.toSet()
        val consoles = ConsoleService.getInstance(project)
        consoles.all().filter { it.dataSource.id in ids }.forEach { consoles.close(it.key) }
    }
}

class DeleteDataSourceAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.nodes().singleOrNull() is ExplorerNode.DataSource }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.nodes().single() as ExplorerNode.DataSource
        if (Messages.showYesNoDialog(project, "Remove data source '${n.config.name}'?", "Remove Data Source", AllIcons.General.QuestionDialog) != Messages.YES) return
        dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).remove(n.config.id)
        ModelService.getInstance(project).remove(n.config.id)
    }
}

/** Quick documentation for a table node: path and generated DDL. */
fun explorerDocText(project: Project, n: ExplorerNode.Table): String =
    "Data source: ${n.config.name}\nDatabase: ${n.database}\nSchema: ${n.schema}\n${if (n.table.isView) "View" else "Table"}: ${n.table.name}\n\n" +
        ExplorerActions.ddlText(project, n)

/** Explorer toolbar: shows a filter field that hides objects whose names do not match. */
class ExplorerFilterAction : com.intellij.openapi.project.DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent) = e.getData(ExplorerDataKeys.PANEL)?.filterShown == true
    override fun setSelected(e: AnActionEvent, state: Boolean) { e.getData(ExplorerDataKeys.PANEL)?.showFilter(state) }
}

/** Copy Reference: the qualified name of the selected objects. */
class CopyReferenceAction : ExplorerAction() {
    private fun names(e: AnActionEvent) = e.getData(ExplorerDataKeys.SELECTED).orEmpty().mapNotNull { qualifiedName(it) }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = names(e).isNotEmpty() }
    override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(names(e).joinToString("\n")))

    companion object {
        fun qualifiedName(n: ExplorerNode): String? = when (n) {
            is ExplorerNode.Table -> "${n.schema}.${n.table.name}"
            is ExplorerNode.Column -> "${n.schema}.${n.table.name}.${n.column.name}"
            is ExplorerNode.Schema -> n.schema.name
            is ExplorerNode.Database -> n.db.name
            is ExplorerNode.DataSource -> n.config.name
            else -> null
        }
    }
}

/** Color Settings: a colour for the data source, shown in the explorer and on its console tabs. */
class DataSourceColorAction : ExplorerAction() {
    private fun config(e: AnActionEvent) = (e.getData(ExplorerDataKeys.SELECTED)?.singleOrNull() as? ExplorerNode.DataSource)?.config
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = config(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = config(e) ?: return
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<Pair<String, String?>>("Color", COLORS) {
            override fun getTextFor(value: Pair<String, String?>) = value.first
            override fun getIconFor(value: Pair<String, String?>) = value.second?.let { com.intellij.util.ui.ColorIcon(12, java.awt.Color.decode(it)) }
            override fun onChosen(selectedValue: Pair<String, String?>, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                doFinalStep { setColor(project, config, selectedValue.second) }
                return FINAL_CHOICE
            }
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(e.dataContext)
    }

    companion object {
        val COLORS = listOf<Pair<String, String?>>("None" to null, "Red" to "#E5534B", "Orange" to "#E8A33D", "Yellow" to "#D9C84A",
            "Green" to "#5FB865", "Blue" to "#4A8FE7", "Violet" to "#9A6FE0", "Gray" to "#8C8C8C")

        fun setColor(project: com.intellij.openapi.project.Project, config: dev.teo.mydb.core.datasource.DataSourceConfig, color: String?) {
            dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).save(config.copy(color = color))
            com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).openFiles.forEach {
                com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(it)
            }
        }
    }
}

/** Console and SQL file tabs take the colour of their data source. */
class DataSourceTabColorProvider : com.intellij.openapi.fileEditor.impl.EditorTabColorProvider, com.intellij.openapi.project.DumbAware {
    override fun getEditorTabColor(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile): java.awt.Color? {
        val id = dev.teo.mydb.plugin.sql.SqlDataSourceBinding.getInstance(project).dataSourceId(file) ?: return null
        val c = dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).find(id)?.color ?: return null
        return runCatching { com.intellij.ui.ColorUtil.withAlpha(java.awt.Color.decode(c), 0.25) }.getOrNull()
    }
}
