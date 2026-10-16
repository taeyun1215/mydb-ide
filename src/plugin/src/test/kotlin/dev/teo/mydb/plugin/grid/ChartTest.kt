package dev.teo.mydb.plugin.grid

import com.intellij.openapi.application.ApplicationManager
import dev.teo.mydb.core.chart.ChartType
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.console.ConsoleService
import java.awt.image.BufferedImage

/** a result as a chart. */
class ChartTest : MyDbTestCase() {
    fun `test chart groups rows by the x column and paints every type`() {
        val handle = ApplicationManager.getApplication().executeOnPooledThread<dev.teo.mydb.plugin.console.SessionHandle> {
            ConsoleService.getInstance(project).session("chart", "chart", dataSource)
        }.get()
        try {
            val page = (handle.session.execute("select status, count(*) as orders, sum(id) as ids from shop.orders group by status order by status", 100) as StatementResult.Rows).page
            val editor = ChartEditor(ChartFile("Chart: test", page))
            assertEquals(0, editor.x.selectedItem)
            assertEquals(listOf(1), editor.chosenSeries())
            assertEquals(page.rows.map { it[0].toString() }, editor.canvas.data.categories)
            for (t in ChartType.entries) {
                editor.type.selectedItem = t
                val img = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB)
                editor.canvas.setSize(600, 400)
                editor.canvas.paint(img.graphics)
                assertEquals(t, editor.canvas.type)
            }
        } finally { ConsoleService.getInstance(project).close("chart") }
    }
}
