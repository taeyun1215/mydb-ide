package dev.teo.mydb.plugin.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/**
 * Database → SQL Resolution Scopes: which data source resolves the SQL files of a directory
 * when a file is not attached to one itself.
 */
class ResolutionScopesConfigurable(private val project: Project) : SearchableConfigurable {
    private val rows = mutableListOf<Pair<String, String>>()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Directory or file" else "Data source"
        override fun isCellEditable(row: Int, column: Int) = column == 1
        override fun getValueAt(row: Int, column: Int): Any = if (column == 0) VirtualFileManager.extractPath(rows[row].first)
            else DataSourceStorage.getInstance(project).find(rows[row].second)?.name ?: rows[row].second
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val ds = DataSourceStorage.getInstance(project).all().firstOrNull { it.name == value } ?: return
            rows[row] = rows[row].first to ds.id
            fireTableRowsUpdated(row, row)
        }
    }

    override fun getId() = "mydb.database.scopes"
    override fun getDisplayName() = "SQL Resolution Scopes"

    override fun createComponent(): JComponent {
        reset()
        val table = JBTable(model)
        table.columnModel.getColumn(1).cellEditor = DefaultCellEditor(ComboBox(DataSourceStorage.getInstance(project).all().map { it.name }.toTypedArray()))
        return ToolbarDecorator.createDecorator(table)
            .setAddAction {
                val ds = DataSourceStorage.getInstance(project).all().firstOrNull() ?: return@setAddAction
                val dir = FileChooser.chooseFile(FileChooserDescriptorFactory.singleFileOrDir(), project, null) ?: return@setAddAction
                rows += dir.url to ds.id
                model.fireTableDataChanged()
            }
            .setRemoveAction { table.selectedRow.takeIf { it >= 0 }?.let { rows.removeAt(it); model.fireTableDataChanged() } }
            .disableUpDownActions()
            .createPanel()
    }

    /** Rows shown in the page (tests). */
    fun shownRows(): List<Pair<String, String>> = rows.toList()
    fun addRow(url: String, dataSourceId: String) { rows += url to dataSourceId; model.fireTableDataChanged() }

    override fun isModified() = rows.toSet() != SqlDataSourceBinding.getInstance(project).scopes().toSet()
    override fun apply() = SqlDataSourceBinding.getInstance(project).setScopes(rows.toList())
    override fun reset() { rows.clear(); rows += SqlDataSourceBinding.getInstance(project).scopes(); model.fireTableDataChanged() }
}
