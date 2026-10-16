package dev.teo.mydb.plugin.transfer

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.core.transfer.ImportColumn
import dev.teo.mydb.core.transfer.ImportOptions
import dev.teo.mydb.core.transfer.ImportPlan
import dev.teo.mydb.core.transfer.ImportResult
import dev.teo.mydb.plugin.model.ModelService
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableModel

/**
 * "Import" for one CSV file into a new table (named after the file, inferred types)
 * an existing table (columns by position). Multiple targets per file are not supported yet.
 */
class ImportDialog(
    private val project: Project,
    private val config: DataSourceConfig,
    private val database: String,
    schema: String,
    private val file: Path,
    private val existing: DbTable?,
) : DialogWrapper(project, true) {

    private val schemas = ModelService.getInstance(project).model(config.id)?.databases?.firstOrNull { it.name == database }
        ?.schemas?.map { it.name }.orEmpty().ifEmpty { listOf(schema) }
    private val schemaBox = ComboBox(schemas.toTypedArray()).apply { selectedItem = schema; isEnabled = existing == null }
    private val tableName = JBTextField().apply { isEditable = existing == null }
    private val tag = JBLabel().apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() }
    private val errorsToFile = JBCheckBox("Write errors to file", true)
    private val unconvertibleAsNull = JBCheckBox("Insert values that cannot be converted as NULL")
    private val mapping = MappingModel()
    private val ddl = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12) }
    private val preview = DefaultTableModel()
    private val errorLog: Path = Files.createTempFile("mydb-import-errors", ".txt")
    private var sourceHeader: List<String?> = emptyList()

    init {
        title = "Import: ${file.fileName}"
        val plan = DataImport.plan(file, schema, existing, ImportOptions())
        tableName.text = plan.table
        tag.text = if (existing != null) "existing" else "new: auto"
        mapping.columns = plan.columns.toMutableList()
        sourceHeader = DataImport.previewRows(file, ImportOptions(), limit = 0).firstOrNull().orEmpty()
        DataImport.previewRows(file, ImportOptions()).let { rows ->
            preview.setColumnIdentifiers(rows.firstOrNull()?.toTypedArray() ?: emptyArray())
            rows.drop(1).forEach { preview.addRow(it.toTypedArray()) }
        }
        tableName.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = refreshDdl() })
        schemaBox.addActionListener { refreshDdl() }
        mapping.addTableModelListener { refreshDdl() }
        init()
        refreshDdl()
    }

    fun options() = ImportOptions(insertUnconvertibleAsNull = unconvertibleAsNull.isSelected, errorLog = errorLog.takeIf { errorsToFile.isSelected })

    fun plan() = ImportPlan(schemaBox.selectedItem as String, tableName.text.trim(), existing == null, mapping.columns.toList(),
        dev.teo.mydb.core.exec.SqlDialect.of(config.dbms))

    private fun refreshDdl() {
        ddl.text = if (existing == null) plan().ddl() else "-- importing into existing table ${plan().schema}.${plan().table}"
    }

    override fun doValidate(): ValidationInfo? = when {
        tableName.text.isBlank() -> ValidationInfo("Enter a table name", tableName)
        mapping.columns.any { it.name.isBlank() } -> ValidationInfo("Every target column needs a name")
        else -> null
    }

    override fun doOKAction() {
        if (!okAction.isEnabled) return
        val plan = plan()
        val options = options()
        isOKActionEnabled = false
        setErrorText(null)
        object : Task.Backgroundable(project, "Importing ${file.fileName}", true) {
            private var result: ImportResult? = null
            override fun run(indicator: ProgressIndicator) { result = DataImport.run(project, config, database, plan, file, options) }
            override fun onSuccess() { close(OK_EXIT_CODE) }
            override fun onThrowable(error: Throwable) {
                val sql = error as? java.sql.SQLException ?: error.cause as? java.sql.SQLException
                setErrorText(if (sql != null) "[${sql.sqlState ?: ""}] ${sql.message}" else error.message ?: error.toString())
                isOKActionEnabled = true
            }
        }.queue()
    }

    /** Esc asks before discarding the settings. */
    override fun doCancelAction() {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(SKIP_CONFIRM_KEY)) return super.doCancelAction()
        val discard = MessageDialogBuilder.yesNo("Import", "The import settings will be lost. Close the dialog?")
            .doNotAsk(object : com.intellij.openapi.ui.DoNotAskOption.Adapter() {
                override fun rememberChoice(isSelected: Boolean, exitCode: Int) { if (isSelected && exitCode == 0) props.setValue(SKIP_CONFIRM_KEY, true) }
            })
            .ask(contentPane)
        if (discard) super.doCancelAction()
    }

    override fun createCenterPanel(): JComponent {
        val top = panel {
            row("Source:") { label(file.toString()) }
            row("Schema:") { cell(schemaBox) }
            row("Table:") { cell(tableName).align(AlignX.FILL); cell(tag) }
            row { cell(JBScrollPane(JBTable(mapping)).apply { preferredSize = Dimension(JBUI.scale(600), JBUI.scale(140)) }).align(Align.FILL) }.resizableRow()
            row { cell(errorsToFile); comment(errorLog.toString()) }
            row { cell(unconvertibleAsNull) }
        }
        val tabs = JBTabbedPane().apply {
            addTab("Data Preview", JBScrollPane(JBTable(preview)))
            addTab("DDL Preview", JBScrollPane(ddl))
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(200))
        }
        return panel {
            row { cell(top).align(Align.FILL) }.resizableRow()
            row { cell(tabs).align(Align.FILL) }.resizableRow()
        }
    }

    /** Target column, target type and source column; names and types are editable for new tables. */
    private inner class MappingModel : AbstractTableModel() {
        var columns: MutableList<ImportColumn> = mutableListOf()
            set(value) { field = value; fireTableDataChanged() }

        override fun getRowCount() = columns.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = listOf("Target column", "Type", "Source column")[column]
        override fun isCellEditable(row: Int, column: Int) = existing == null && column < 2
        override fun getValueAt(row: Int, column: Int): Any = columns[row].let { c ->
            when (column) {
                0 -> c.name
                1 -> c.type
                else -> "<auto> (${sourceHeader.getOrNull(c.sourceIndex) ?: "column ${c.sourceIndex + 1}"})"
            }
        }
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val text = value?.toString()?.trim().orEmpty()
            columns[row] = if (column == 0) columns[row].copy(name = text) else columns[row].copy(type = text)
            fireTableRowsUpdated(row, row)
        }
    }

    companion object {
        private const val SKIP_CONFIRM_KEY = "mydb.import.skipCloseConfirmation"
    }
}
