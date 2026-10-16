package dev.teo.mydb.core.transfer

import dev.teo.mydb.core.chart.ChartData
import dev.teo.mydb.core.exec.ResultColumn
import dev.teo.mydb.core.exec.ResultPage
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Chart series from a result page. */
class ChartDataTest {
    private fun col(n: String) = ResultColumn(n, "text", java.sql.Types.VARCHAR, null, null, null, true, -1, -1)
    private val page = ResultPage(listOf(col("status"), col("total"), col("note")), listOf(
        arrayOf<Any?>("new", BigDecimal("10.5"), "a"), arrayOf<Any?>("paid", 4, "b"), arrayOf<Any?>("new", "2", "c"), arrayOf<Any?>(null, null, "d"),
    ), false, 0, 0)

    @Test
    fun `numeric columns and grouped sums`() {
        assertEquals(listOf(1), ChartData.numericColumns(page))
        val d = ChartData.of(page, 0, listOf(1))
        assertEquals(listOf("new", "paid", "<null>"), d.categories)
        assertEquals(listOf(12.5, 4.0, 0.0), d.series.single().values)
        assertEquals("total", d.series.single().name)
        assertEquals(12.5, d.max)
    }
}
