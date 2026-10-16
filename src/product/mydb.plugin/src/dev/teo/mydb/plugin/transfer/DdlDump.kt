package dev.teo.mydb.plugin.transfer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.model.ModelService
import java.nio.file.Files
import java.nio.file.Path

/** "Dump to DDL Data Source": the schema script in a file, and a DDL data source reading it. */
object DdlDump {
    fun dump(project: Project, config: DataSourceConfig, database: String, schema: String, file: Path): DataSourceConfig {
        val model = ModelService.getInstance(project).rawModel(config.id)
        val s = model?.schema(database, schema) ?: error("$schema is not introspected")
        val gen = PgDdlGenerator(DdlOptions(skipOwner = true, qualify = dev.teo.mydb.core.ddl.SchemaQualification.ALWAYS)) { sc, t -> model.schema(database, sc)?.table(t) }
        Files.createDirectories(file.parent)
        Files.writeString(file, gen.schemaScript(s) + "\n")
        val ddl = DataSourceConfig(name = "$schema (DDL)", dbms = Dbms.DDL, url = "ddl:$file")
        DataSourceStorage.getInstance(project).save(ddl)
        ModelService.getInstance(project).refreshNow(ddl)
        return ddl
    }
}

class DumpToDdlDataSourceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun node(e: AnActionEvent) = (e.getData(ExplorerDataKeys.SELECTED)?.singleOrNull() as? ExplorerNode.Schema)?.takeIf { it.config.dbms == Dbms.POSTGRES }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = node(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = node(e) ?: return
        val dlg = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Dump to DDL Data Source", "SQL file for the schema", "sql"), project)
        val target = dlg.save("${n.schema.name}.sql")?.file?.toPath() ?: return
        DdlDump.dump(project, n.config, n.database, n.schema.name, target)
    }
}
