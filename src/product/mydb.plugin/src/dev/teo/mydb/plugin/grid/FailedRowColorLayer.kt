package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridColumn
import com.intellij.database.datagrid.GridRow
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.datagrid.color.ColorLayer
import com.intellij.openapi.ui.MessageType
import java.awt.Color

/**
 * Paints the row whose statement failed in the last submit. Runs after the mutation
 * layer (priority 1) and before selection (3), so the selection stays visible.
 */
class FailedRowColorLayer(private val mutator: () -> JdbcGridHookUp.Mutator?) : ColorLayer {
    private fun failed(row: ModelIndex<GridRow>) = mutator()?.failedRow == row.value

    override fun getCellBackground(row: ModelIndex<GridRow>, column: ModelIndex<GridColumn>, grid: DataGrid, color: Color?): Color? =
        if (failed(row)) MessageType.ERROR.popupBackground else color

    override fun getRowHeaderBackground(row: ModelIndex<GridRow>, grid: DataGrid, color: Color?): Color? =
        if (failed(row)) MessageType.ERROR.popupBackground else color

    override fun getColumnHeaderBackground(column: ModelIndex<GridColumn>, grid: DataGrid, color: Color?): Color? = color

    override fun getPriority() = 2
}
