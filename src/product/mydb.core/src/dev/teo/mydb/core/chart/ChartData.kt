package dev.teo.mydb.core.chart

import dev.teo.mydb.core.exec.ResultPage

enum class ChartType(val title: String) { BAR("Bar"), LINE("Line"), PIE("Pie") }

/** One series: a value per category. */
data class Series(val name: String, val values: List<Double>)

/** Categories (X axis) and the series over them. */
data class ChartData(val categories: List<String>, val series: List<Series>) {
    val max get() = series.flatMap { it.values }.maxOrNull() ?: 0.0
    val min get() = series.flatMap { it.values }.minOrNull() ?: 0.0

    companion object {
        /** Columns whose values are all numbers (or null): candidates for series. */
        fun numericColumns(page: ResultPage): List<Int> = page.columns.indices.filter { c ->
            page.rows.isNotEmpty() && page.rows.all { r -> r[c] == null || r[c] is Number || r[c].toString().toDoubleOrNull() != null }
        }

        /**
         * Rows grouped by the [x] column (text), each series summing column [ys] per group; categories keep the
         * order of first appearance. Nulls count as 0.
         */
        fun of(page: ResultPage, x: Int, ys: List<Int>): ChartData {
            val categories = LinkedHashMap<String, MutableList<Array<Any?>>>()
            for (r in page.rows) categories.getOrPut(r[x]?.toString() ?: "<null>") { mutableListOf() } += r
            val series = ys.map { y ->
                Series(page.columns[y].label, categories.values.map { rows -> rows.sumOf { (it[y] as? Number)?.toDouble() ?: it[y]?.toString()?.toDoubleOrNull() ?: 0.0 } })
            }
            return ChartData(categories.keys.toList(), series)
        }
    }
}
