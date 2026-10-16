package dev.teo.mydb.core.transfer

import dev.teo.mydb.core.datadiff.DataDiff
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Data compare of two result sets. */
class DataDiffTest {
    private val left = TableData(listOf("id", "title", "price"), listOf(
        listOf(1, "a", BigDecimal("2.50")), listOf(2, "b", BigDecimal("3.00")), listOf(3, "c", BigDecimal("1.00"))))
    private val right = TableData(listOf("id", "price", "title", "extra"), listOf(
        listOf(1, BigDecimal("2.5"), "a", "x"), listOf(2, BigDecimal("4.00"), "b", "y"), listOf(4, BigDecimal("9"), "d", "z")))

    @Test
    fun `rows are matched by key and compared on common columns`() {
        val d = DataDiff.compare(left, right, listOf("id"))
        assertEquals(listOf("id", "title", "price"), d.columns)
        assertEquals(1, d.same)
        assertEquals(listOf("2"), d.changed.map { it.first[0].toString() })
        assertEquals(listOf("3"), d.onlyLeft.map { it[0].toString() })
        assertEquals(listOf("4"), d.onlyRight.map { it[0].toString() })
        assertEquals("1 changed, 1 only on the left, 1 only on the right, 1 identical", d.summary())
    }

    @Test
    fun `without a key whole rows are compared and text is sorted`() {
        val d = DataDiff.compare(left, left.copy(rows = left.rows.reversed()))
        assertTrue(d.identical)
        assertEquals("id\ttitle\tprice\n1\ta\t2.5\n2\tb\t3\n3\tc\t1\n", DataDiff.text(left.copy(rows = left.rows.reversed()), listOf("id")))
    }
}
