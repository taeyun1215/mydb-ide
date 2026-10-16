package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.GridColumn
import com.intellij.database.extractors.BaseObjectFormatter
import com.intellij.database.extractors.ObjectFormatterConfig
import java.math.BigDecimal

/**
 * Shows decimals with the column's declared scale: `numeric(10,2)` value 2.5 is shown as `2.50`.
 * Other numbers keep the default formatting.
 */
class ScaleAwareFormatter : BaseObjectFormatter() {
    override fun numberToString(o: Number, column: GridColumn?, config: ObjectFormatterConfig): String {
        val scale = (column as? com.intellij.database.datagrid.DataConsumer.Column)?.scale ?: -1
        if (o is BigDecimal && scale in 1..MAX_SCALE && o.scale() <= scale) {
            return super.numberToString(o, column, config).let { shown ->
                // keep the default grouping/sign, then pad the fraction to the declared scale
                val fraction = shown.substringAfter('.', "")
                if (shown.contains('E')) shown
                else if (fraction.length >= scale) shown
                else (if (shown.contains('.')) shown else "$shown.") + "0".repeat(scale - fraction.length)
            }
        }
        return super.numberToString(o, column, config)
    }

    companion object {
        private const val MAX_SCALE = 30
    }
}
