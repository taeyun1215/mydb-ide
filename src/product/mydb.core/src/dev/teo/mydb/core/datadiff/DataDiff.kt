package dev.teo.mydb.core.datadiff

import dev.teo.mydb.core.transfer.TableData

/** Row-level difference of two data sets matched by key columns. */
data class DataDiffResult(
    val columns: List<String>,
    val onlyLeft: List<List<Any?>>,
    val onlyRight: List<List<Any?>>,
    /** Rows present on both sides whose non-key values differ: (left, right). */
    val changed: List<Pair<List<Any?>, List<Any?>>>,
    val same: Int,
) {
    val identical get() = onlyLeft.isEmpty() && onlyRight.isEmpty() && changed.isEmpty()
    fun summary() = "${changed.size} changed, ${onlyLeft.size} only on the left, ${onlyRight.size} only on the right, $same identical"
}

/**
 * Compares two result sets column by name. Rows are matched by [key] columns, or by all common columns
 * when no key is given (then a row is either equal or missing on one side).
 */
object DataDiff {
    fun compare(left: TableData, right: TableData, key: List<String> = emptyList()): DataDiffResult {
        val columns = left.columns.filter { it in right.columns }
        val keyCols = key.filter { it in columns }.ifEmpty { columns }
        fun project(d: TableData) = d.rows.map { r -> columns.map { c -> norm(r[d.columns.indexOf(c)]) } }
        val l = project(left); val r = project(right)
        val ki = keyCols.map(columns::indexOf)
        fun keyOf(row: List<Any?>) = ki.map { row[it] }
        val rightByKey = r.groupBy(::keyOf)
        val leftByKey = l.groupBy(::keyOf)
        val onlyLeft = mutableListOf<List<Any?>>(); val changed = mutableListOf<Pair<List<Any?>, List<Any?>>>(); var same = 0
        for ((k, rows) in leftByKey) {
            val others = rightByKey[k].orEmpty()
            rows.forEachIndexed { i, row ->
                val other = others.getOrNull(i)
                when {
                    other == null -> onlyLeft += row
                    other == row -> same++
                    else -> changed += row to other
                }
            }
        }
        val onlyRight = rightByKey.flatMap { (k, rows) -> rows.drop(leftByKey[k]?.size ?: 0) }
        return DataDiffResult(columns, onlyLeft, onlyRight, changed, same)
    }

    /** Tab-separated text sorted by [key], for a side-by-side text diff. */
    fun text(d: TableData, key: List<String>): String {
        val ki = key.mapNotNull { k -> d.columns.indexOf(k).takeIf { it >= 0 } }
        val rows = d.rows.map { r -> r.map(::norm) }.sortedWith { a, b ->
            for (i in ki.ifEmpty { a.indices.toList() }) { val c = compareValues(a[i]?.toString(), b[i]?.toString()); if (c != 0) return@sortedWith c }
            0
        }
        return (listOf(d.columns) + rows.map { r -> r.map { it?.toString() ?: "<null>" } }).joinToString("\n") { it.joinToString("\t") } + "\n"
    }

    /** Numbers compare by value (`2.50` = `2.5`), everything else by text. */
    private fun norm(v: Any?): Any? = when (v) {
        is java.math.BigDecimal -> v.stripTrailingZeros().toPlainString()
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        null -> null
        else -> v.toString()
    }
}
