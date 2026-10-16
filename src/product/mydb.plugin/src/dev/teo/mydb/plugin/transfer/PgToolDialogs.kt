package dev.teo.mydb.plugin.transfer

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.JdbcUrls
import dev.teo.mydb.core.transfer.DumpFormat
import dev.teo.mydb.core.transfer.DumpOptions
import dev.teo.mydb.core.transfer.PgTarget
import dev.teo.mydb.core.transfer.PgTools
import dev.teo.mydb.core.transfer.RestoreOptions
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.settings.MyDbSettings
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Runs a client tool with the data source password in `PGPASSWORD` and shows its output in the Run tool window. */
object PgToolRunner {
    fun start(project: Project, title: String, command: List<String>, config: DataSourceConfig, show: Boolean = true, input: java.io.File? = null): ProcessHandler {
        val line = GeneralCommandLine(command).withCharset(Charsets.UTF_8)
        val passwordVariable = if (config.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL) "MYSQL_PWD" else "PGPASSWORD"
        DataSourceStorage.getInstance(project).password(config.id)?.let { line.withEnvironment(passwordVariable, it) }
        input?.let { line.withInput(it) }
        val handler = OSProcessHandler(line)
        if (show) RunContentExecutor(project, handler).withTitle(title).withActivateToolWindow(true).run()
        else handler.startNotify()
        return handler
    }

    fun target(config: DataSourceConfig, database: String): PgTarget {
        val parts = JdbcUrls.parse(config.dbms, config.url)
        return PgTarget(parts?.host ?: "localhost", parts?.port, config.user, database)
    }
}

/** Explorer → Import/Export → Export with pg_dump: a schema, tables or the whole database. */
class PgDumpDialog(private val project: Project, private val config: DataSourceConfig, private val database: String,
                   private val schemas: List<String>, private val tables: List<String>) : DialogWrapper(project, true) {
    private val settings = MyDbSettings.get()
    val tool = TextFieldWithBrowseButton().apply {
        text = settings.pgDumpPath.ifBlank { PgTools.locate("pg_dump").orEmpty() }
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("pg_dump"))
    }
    val format = ComboBox(DumpFormat.entries.toTypedArray()).apply { renderer = SimpleListCellRenderer.create("") { it.title } }
    val output = TextFieldWithBrowseButton().apply {
        text = Path.of(System.getProperty("user.home"), "${(tables.singleOrNull() ?: schemas.singleOrNull() ?: database).replace('.', '_')}.sql").toString()
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFileOrDir().withTitle("Output"))
    }
    val schemaOnly = JBCheckBox("Schema only")
    val dataOnly = JBCheckBox("Data only")
    val clean = JBCheckBox("Drop objects before creating (--clean)")
    val ifExists = JBCheckBox("Use IF EXISTS")
    val noOwner = JBCheckBox("No owner")
    val inserts = JBCheckBox("Data as INSERT statements")
    val extra = JBTextField()
    private val commandPreview = JBTextArea(3, 60).apply { isEditable = false; lineWrap = true }

    init {
        title = "Export with pg_dump"
        format.addActionListener {
            val f = format.selectedItem as DumpFormat
            output.text = output.text.substringBeforeLast('.').let { if (f.extension.isEmpty()) it else "$it.${f.extension}" }
            refresh()
        }
        listOf(tool.textField, output.textField, extra).forEach { it.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = refresh() }) }
        listOf(schemaOnly, dataOnly, clean, ifExists, noOwner, inserts).forEach { it.addActionListener { refresh() } }
        init()
        refresh()
    }

    fun options() = DumpOptions(tool.text.trim(), PgToolRunner.target(config, database), output.text.trim(), format.selectedItem as DumpFormat,
        schemas, tables, schemaOnly.isSelected, dataOnly.isSelected, clean.isSelected, ifExists.isSelected, noOwner.isSelected, inserts.isSelected, extra.text)

    private fun refresh() {
        commandPreview.text = PgTools.dump(options()).joinToString(" ")
        isOKActionEnabled = tool.text.isNotBlank() && output.text.isNotBlank()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("pg_dump:") { cell(tool).align(AlignX.FILL) }
        row("What:") { label(if (tables.isNotEmpty()) "tables ${tables.joinToString(", ")}" else if (schemas.isNotEmpty()) "schema ${schemas.joinToString(", ")}" else "database $database") }
        row("Format:") { cell(format) }
        row("Output:") { cell(output).align(AlignX.FILL) }
        row { cell(schemaOnly); cell(dataOnly) }
        row { cell(clean); cell(ifExists) }
        row { cell(noOwner); cell(inserts) }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL) }
        row("Command:") {}
        row { cell(commandPreview).align(AlignX.FILL) }
    }

    override fun doOKAction() {
        settings.pgDumpPath = tool.text.trim()
        PgToolRunner.start(project, "pg_dump $database", PgTools.dump(options()), config)
        super.doOKAction()
    }
}

/** Explorer → Import/Export → Restore with pg_restore (an archive) or psql (a .sql script). */
class PgRestoreDialog(private val project: Project, private val config: DataSourceConfig, private val database: String) : DialogWrapper(project, true) {
    private val settings = MyDbSettings.get()
    val input = TextFieldWithBrowseButton().apply { addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFileOrDir().withTitle("Dump File")) }
    val restoreTool = TextFieldWithBrowseButton().apply {
        text = settings.pgRestorePath.ifBlank { PgTools.locate("pg_restore").orEmpty() }
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("pg_restore"))
    }
    val psqlTool = TextFieldWithBrowseButton().apply {
        text = settings.psqlPath.ifBlank { PgTools.locate("psql").orEmpty() }
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("psql"))
    }
    val clean = JBCheckBox("Drop objects before restoring (--clean)")
    val ifExists = JBCheckBox("Use IF EXISTS")
    val noOwner = JBCheckBox("No owner")
    val singleTransaction = JBCheckBox("Single transaction", true)
    val extra = JBTextField()
    private val commandPreview = JBTextArea(3, 60).apply { isEditable = false; lineWrap = true }

    init {
        title = "Restore into $database"
        listOf(input.textField, restoreTool.textField, psqlTool.textField, extra).forEach { it.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = refresh() }) }
        listOf(clean, ifExists, noOwner, singleTransaction).forEach { it.addActionListener { refresh() } }
        init()
        refresh()
    }

    fun options(): RestoreOptions {
        val tool = if (PgTools.isPlainSql(input.text.trim())) psqlTool.text.trim() else restoreTool.text.trim()
        return RestoreOptions(tool, PgToolRunner.target(config, database), input.text.trim(), clean.isSelected, ifExists.isSelected,
            noOwner.isSelected, singleTransaction.isSelected, extra.text)
    }

    private fun refresh() {
        commandPreview.text = PgTools.restore(options()).joinToString(" ")
        isOKActionEnabled = input.text.isNotBlank() && options().tool.isNotBlank()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("File:") { cell(input).align(AlignX.FILL) }
        row("pg_restore:") { cell(restoreTool).align(AlignX.FILL) }
        row("psql:") { cell(psqlTool).align(AlignX.FILL) }
        row { comment("A .sql file is run with psql, an archive or directory with pg_restore.") }
        row { cell(clean); cell(ifExists) }
        row { cell(noOwner); cell(singleTransaction) }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL) }
        row("Command:") {}
        row { cell(commandPreview).align(AlignX.FILL) }
    }

    override fun doOKAction() {
        settings.pgRestorePath = restoreTool.text.trim(); settings.psqlPath = psqlTool.text.trim()
        val handler = PgToolRunner.start(project, "restore $database", PgTools.restore(options()), config)
        // the restored objects show up after a refresh of the data source
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                if (event.exitCode == 0) dev.teo.mydb.plugin.model.ModelService.getInstance(project).refresh(config)
            }
        })
        super.doOKAction()
    }
}

private fun AnActionEvent.nodes() = getData(ExplorerDataKeys.SELECTED).orEmpty()

class PgDumpAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val n = e.nodes()
        if (n.firstOrNull()?.config?.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL) {
            e.presentation.text = "Export with mysqldump…"
            e.presentation.isEnabledAndVisible = n.all { it is ExplorerNode.Table } || n.singleOrNull() is ExplorerNode.Schema
            return
        }
        e.presentation.text = "Export with pg_dump…"
        e.presentation.isEnabledAndVisible = n.isNotEmpty() && n.first().config.dbms == dev.teo.mydb.core.datasource.Dbms.POSTGRES &&
            (n.all { it is ExplorerNode.Table } || n.all { it is ExplorerNode.Schema } || n.singleOrNull() is ExplorerNode.Database)
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.nodes()
        if (n.firstOrNull()?.config?.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL) {
            when (val first = n.first()) {
                is ExplorerNode.Table -> MysqlDumpDialog(project, first.config, first.schema, n.filterIsInstance<ExplorerNode.Table>().map { it.table.name }).show()
                is ExplorerNode.Schema -> MysqlDumpDialog(project, first.config, first.schema.name, emptyList()).show()
                else -> {}
            }
            return
        }
        when (val first = n.firstOrNull()) {
            is ExplorerNode.Table -> PgDumpDialog(project, first.config, first.database, emptyList(),
                n.filterIsInstance<ExplorerNode.Table>().map { "${it.schema}.${it.table.name}" }).show()
            is ExplorerNode.Schema -> PgDumpDialog(project, first.config, first.database, n.filterIsInstance<ExplorerNode.Schema>().map { it.schema.name }, emptyList()).show()
            is ExplorerNode.Database -> PgDumpDialog(project, first.config, first.db.name, emptyList(), emptyList()).show()
            else -> {}
        }
    }
}

class PgRestoreAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun target(e: AnActionEvent): Pair<DataSourceConfig, String>? = when (val n = e.nodes().singleOrNull()) {
        is ExplorerNode.Database -> n.config to n.db.name
        is ExplorerNode.Schema -> n.config to n.database
        is ExplorerNode.DataSource -> n.config to (JdbcUrls.parse(n.config.dbms, n.config.url)?.database ?: return null)
        else -> null
    }
    override fun update(e: AnActionEvent) {
        val t = target(e)
        val mysql = (e.nodes().singleOrNull() as? ExplorerNode.Schema)?.config?.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL
        e.presentation.text = if (mysql) "Restore with mysql…" else "Restore with pg_restore or psql…"
        e.presentation.isEnabledAndVisible = mysql || t?.first?.dbms == dev.teo.mydb.core.datasource.Dbms.POSTGRES
    }
    override fun actionPerformed(e: AnActionEvent) {
        val schemaNode = e.nodes().singleOrNull() as? ExplorerNode.Schema
        if (schemaNode?.config?.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL) {
            MysqlRestoreDialog(e.project ?: return, schemaNode.config, schemaNode.schema.name).show(); return
        }
        val (config, db) = target(e) ?: return
        PgRestoreDialog(e.project ?: return, config, db).show()
    }
}

/** Explorer → Export with mysqldump: a schema or some of its tables. */
class MysqlDumpDialog(private val project: Project, private val config: DataSourceConfig, private val schema: String,
                      private val tables: List<String>) : DialogWrapper(project, true) {
    private val settings = MyDbSettings.get()
    val tool = TextFieldWithBrowseButton().apply {
        text = settings.mysqldumpPath.ifBlank { PgTools.locate("mysqldump").orEmpty() }
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("mysqldump"))
    }
    val output = TextFieldWithBrowseButton().apply {
        text = Path.of(System.getProperty("user.home"), (tables.singleOrNull()?.let { "${schema}_$it" } ?: schema) + ".sql").toString()
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Output"))
    }
    val noData = JBCheckBox("Structure only (--no-data)")
    val noCreate = JBCheckBox("Data only (--no-create-info)")
    val dropTable = JBCheckBox("Add DROP TABLE", true)
    val extra = JBTextField()
    private val commandPreview = JBTextArea(3, 60).apply { isEditable = false; lineWrap = true }

    init {
        title = "Export with mysqldump"
        listOf(tool.textField, output.textField, extra).forEach { it.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = refresh() }) }
        listOf(noData, noCreate, dropTable).forEach { it.addActionListener { refresh() } }
        init(); refresh()
    }

    fun options() = dev.teo.mydb.core.transfer.MysqlDumpOptions(tool.text.trim(), PgToolRunner.target(config, schema), output.text.trim(), schema, tables,
        noData.isSelected, noCreate.isSelected, dropTable.isSelected, extra.text)

    private fun refresh() {
        commandPreview.text = dev.teo.mydb.core.transfer.MysqlTools.dump(options()).joinToString(" ")
        isOKActionEnabled = tool.text.isNotBlank() && output.text.isNotBlank()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("mysqldump:") { cell(tool).align(AlignX.FILL) }
        row("What:") { label(if (tables.isNotEmpty()) "tables ${tables.joinToString(", ")} of $schema" else "schema $schema") }
        row("Output:") { cell(output).align(AlignX.FILL) }
        row { cell(noData); cell(noCreate) }
        row { cell(dropTable) }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL) }
        row("Command:") {}
        row { cell(commandPreview).align(AlignX.FILL) }
    }

    override fun doOKAction() {
        settings.mysqldumpPath = tool.text.trim()
        PgToolRunner.start(project, "mysqldump $schema", dev.teo.mydb.core.transfer.MysqlTools.dump(options()), config)
        super.doOKAction()
    }
}

/** Explorer → Restore with mysql: runs a SQL script file into a schema (the file is the tool's standard input). */
class MysqlRestoreDialog(private val project: Project, private val config: DataSourceConfig, private val schema: String) : DialogWrapper(project, true) {
    private val settings = MyDbSettings.get()
    val input = TextFieldWithBrowseButton().apply { addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("SQL Script")) }
    val tool = TextFieldWithBrowseButton().apply {
        text = settings.mysqlPath.ifBlank { PgTools.locate("mysql").orEmpty() }
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("mysql"))
    }
    val extra = JBTextField()

    init { title = "Restore into $schema"; init() }

    fun command() = dev.teo.mydb.core.transfer.MysqlTools.restore(tool.text.trim(), PgToolRunner.target(config, schema), schema, extra.text)

    override fun createCenterPanel(): JComponent = panel {
        row("Script:") { cell(input).align(AlignX.FILL) }
        row("mysql:") { cell(tool).align(AlignX.FILL) }
        row("Extra arguments:") { cell(extra).align(AlignX.FILL) }
    }

    override fun doOKAction() {
        settings.mysqlPath = tool.text.trim()
        val handler = PgToolRunner.start(project, "restore $schema", command(), config, input = java.io.File(input.text.trim()))
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                if (event.exitCode == 0) dev.teo.mydb.plugin.model.ModelService.getInstance(project).refresh(config)
            }
        })
        super.doOKAction()
    }
}
