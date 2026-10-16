package dev.teo.mydb.plugin.datasource

import dev.teo.mydb.core.connection.DriverProperties
import dev.teo.mydb.core.connection.DriverProperty
import dev.teo.mydb.core.datasource.DataSourceConfig
import javax.swing.table.AbstractTableModel

/**
 * Driver property table of the advanced tab. Rows come from the driver; editing a value
 * makes it an override, setting it back to the default (or resetting the row) removes the override.
 */
class DriverPropertiesModel(config: DataSourceConfig) : AbstractTableModel() {
    private var rows: MutableList<DriverProperty> = runCatching { DriverProperties.describe(config) }
        .getOrElse { config.driverProperties.map { (k, v) -> DriverProperty(k, v, null, null, emptyList(), true) } }.toMutableList()

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) "Name" else "Value"
    override fun isCellEditable(row: Int, column: Int) = column == 1 || rows[row].defaultValue == null && !rows[row].changed
    override fun getValueAt(row: Int, column: Int): Any? = rows[row].let { if (column == 0) it.name else it.value }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val r = rows[row]
        val text = (value as? String)?.trim()
        rows[row] = if (column == 0) r.copy(name = text.orEmpty())
        else r.copy(value = text?.ifEmpty { null } ?: r.defaultValue, changed = !text.isNullOrEmpty() && text != r.defaultValue)
        fireTableRowsUpdated(row, row)
    }

    fun isChanged(row: Int) = rows.getOrNull(row)?.changed == true
    fun descriptionAt(row: Int) = rows.getOrNull(row)?.description

    fun addCustom() { rows += DriverProperty("", null, null, null, emptyList(), changed = true); fireTableRowsInserted(rows.size - 1, rows.size - 1) }

    /** Back to the driver default; custom properties are removed. */
    fun reset(row: Int) {
        val r = rows[row]
        if (r.defaultValue == null && r.description == null) { rows.removeAt(row); fireTableRowsDeleted(row, row) }
        else { rows[row] = r.copy(value = r.defaultValue, changed = false); fireTableRowsUpdated(row, row) }
    }

    fun overrides(): Map<String, String> = rows.filter { it.changed && it.name.isNotBlank() }.associate { it.name to it.value.orEmpty() }
}
