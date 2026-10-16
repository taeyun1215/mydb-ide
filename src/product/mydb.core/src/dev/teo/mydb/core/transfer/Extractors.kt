package dev.teo.mydb.core.transfer

import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.temporal.TemporalAccessor

/** Tabular data to export: header plus rows of JDBC values. */
data class TableData(val columns: List<String>, val rows: List<List<Any?>>, val sourceTable: String? = null)

data class ExportOptions(
    val transpose: Boolean = false,
    val columnHeader: Boolean = false,
    val rowHeader: Boolean = false,
)

/** Output of an extractor: text or binary (xlsx). */
sealed interface Exported {
    data class Text(val text: String) : Exported
    data class Binary(val bytes: ByteArray) : Exported
}

/** One export format. [id] is stable and used in settings; [title] is shown in the UI. */
interface Extractor {
    val id: String
    val title: String
    val extension: String
    val supportsHeaders: Boolean get() = false
    fun export(data: TableData, options: ExportOptions): Exported
}

object Extractors {
    val all: List<Extractor> = listOf(
        SqlInsert(multiRow = false, id = "sql-insert", title = "SQL Insert"),
        SqlUpdate,
        WhereClause,
        Xlsx,
        Delimited("csv", "CSV", "csv", ','),
        Delimited("tsv", "TSV", "tsv", '\t'),
        Delimited("pipe", "Pipe-separated", "txt", '|'),
        Delimited("semicolon", "Semicolon-separated", "csv", ';'),
        Html,
        HtmlPage,
        JsonExtractor,
        Markdown,
        OneRow,
        Pretty,
        PythonDataFrame,
        SqlInsert(multiRow = true, id = "sql-insert-multirow", title = "SQL Insert (multi-row)"),
        XmlExtractor,
    )

    fun byId(id: String) = all.first { it.id == id }
}

// ---- helpers ------------------------------------------------------------------------------------

internal fun cellText(v: Any?): String = when (v) {
    null -> ""
    is ByteArray -> v.joinToString("") { "%02x".format(it) }
    else -> v.toString()
}

internal fun sqlLiteral(v: Any?): String = when (v) {
    null -> "null"
    is Number, is Boolean -> v.toString()
    else -> "'" + cellText(v).replace("'", "''") + "'"
}

/** Applies transpose and header options to a plain grid of strings. */
private fun grid(data: TableData, o: ExportOptions): List<List<String>> {
    var rows = data.rows.map { r -> r.map(::cellText) }
    var header: List<String>? = if (o.columnHeader) data.columns else null
    if (o.transpose) {
        val t = data.columns.indices.map { c -> rows.map { it[c] } }
        rows = t.mapIndexed { i, r -> if (o.columnHeader) listOf(data.columns[i]) + r else r }
        header = null
    }
    if (o.rowHeader && !o.transpose) {
        rows = rows.mapIndexed { i, r -> listOf((i + 1).toString()) + r }
        header = header?.let { listOf("") + it }
    }
    return listOfNotNull(header) + rows
}

class Delimited(override val id: String, override val title: String, override val extension: String, private val sep: Char) : Extractor {
    override val supportsHeaders = true
    override fun export(data: TableData, options: ExportOptions) =
        Exported.Text(grid(data, options).joinToString("\n", postfix = "\n") { row -> row.joinToString(sep.toString()) { quote(it) } })

    /** RFC 4180 style quoting: only when the value contains the separator, a quote or a line break. */
    private fun quote(v: String) = if (v.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

class SqlInsert(private val multiRow: Boolean, override val id: String, override val title: String) : Extractor {
    override val extension = "sql"
    override fun export(data: TableData, options: ExportOptions): Exported {
        val table = data.sourceTable ?: "my_table"
        val cols = data.columns.joinToString(", ") { quote(it) }
        if (multiRow) {
            val values = data.rows.joinToString(",\n       ") { r -> r.joinToString(", ", "(", ")") { sqlLiteral(it) } }
            return Exported.Text("INSERT INTO $table ($cols)\nVALUES $values;\n")
        }
        return Exported.Text(data.rows.joinToString("") { r -> "INSERT INTO $table ($cols) VALUES (${r.joinToString(", ") { sqlLiteral(it) }});\n" })
    }
}

object SqlUpdate : Extractor {
    override val id = "sql-update"; override val title = "SQL Update"; override val extension = "sql"
    override fun export(data: TableData, options: ExportOptions): Exported {
        val table = data.sourceTable ?: "my_table"
        return Exported.Text(data.rows.joinToString("") { r ->
            val sets = data.columns.zip(r).drop(1).joinToString(", ") { (c, v) -> "${quote(c)} = ${sqlLiteral(v)}" }
            "UPDATE $table SET $sets WHERE ${quote(data.columns.first())} = ${sqlLiteral(r.first())};\n"
        })
    }
}

object WhereClause : Extractor {
    override val id = "where"; override val title = "WHERE clause"; override val extension = "sql"
    override fun export(data: TableData, options: ExportOptions): Exported {
        if (data.columns.size == 1) return Exported.Text("${quote(data.columns.single())} IN (${data.rows.joinToString(", ") { sqlLiteral(it.single()) }})")
        return Exported.Text(data.rows.joinToString(" OR ") { r -> data.columns.zip(r).joinToString(" AND ", "(", ")") { (c, v) -> if (v == null) "${quote(c)} IS NULL" else "${quote(c)} = ${sqlLiteral(v)}" } })
    }
}

object Html : Extractor {
    override val id = "html"; override val title = "HTML"; override val extension = "html"
    override val supportsHeaders = true
    override fun export(data: TableData, options: ExportOptions): Exported {
        val g = grid(data, options.copy(columnHeader = true))
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val head = g.first().joinToString("") { "<th>${esc(it)}</th>" }
        val body = g.drop(1).joinToString("\n") { r -> "  <tr>" + r.joinToString("") { "<td>${esc(it)}</td>" } + "</tr>" }
        return Exported.Text("<table>\n  <tr>$head</tr>\n$body\n</table>\n")
    }
}

/** A complete HTML page: title, a styled table with a header row and zebra rows. */
object HtmlPage : Extractor {
    override val id = "html-page"; override val title = "HTML Page"; override val extension = "html"
    override val supportsHeaders = true
    override fun export(data: TableData, options: ExportOptions): Exported {
        val table = (Html.export(data, options) as Exported.Text).text
        val name = data.sourceTable ?: "Data"
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return Exported.Text("""<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<title>${esc(name)}</title>
<style>
  body { font-family: -apple-system, "Segoe UI", sans-serif; margin: 16px; }
  table { border-collapse: collapse; }
  th, td { border: 1px solid #ccc; padding: 4px 8px; text-align: left; }
  th { background: #f0f0f0; }
  tr:nth-child(even) td { background: #fafafa; }
</style>
</head>
<body>
<h3>${esc(name)}</h3>
$table</body>
</html>
""")
    }
}

object JsonExtractor : Extractor {
    override val id = "json"; override val title = "JSON"; override val extension = "json"
    override fun export(data: TableData, options: ExportOptions): Exported {
        fun v(x: Any?): String = when (x) {
            null -> "null"
            is Number, is Boolean -> x.toString()
            else -> "\"" + cellText(x).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
        }
        return Exported.Text(data.rows.joinToString(",\n", "[\n", "\n]\n") { r ->
            data.columns.zip(r).joinToString(",\n", "  {\n", "\n  }") { (c, x) -> "    \"$c\": ${v(x)}" }
        })
    }
}

object Markdown : Extractor {
    override val id = "markdown"; override val title = "Markdown"; override val extension = "md"
    override fun export(data: TableData, options: ExportOptions): Exported {
        fun esc(s: String) = s.replace("|", "\\|").replace("\n", "<br>")
        val head = data.columns.joinToString(" | ", "| ", " |") { esc(it) }
        val sep = data.columns.joinToString(" | ", "| ", " |") { "---" }
        val rows = data.rows.joinToString("\n") { r -> r.joinToString(" | ", "| ", " |") { esc(cellText(it)) } }
        return Exported.Text("$head\n$sep\n$rows\n")
    }
}

object OneRow : Extractor {
    override val id = "one-row"; override val title = "One row"; override val extension = "txt"
    override fun export(data: TableData, options: ExportOptions) =
        Exported.Text(data.rows.flatten().joinToString(", ") { sqlLiteral(it) })
}

object Pretty : Extractor {
    override val id = "pretty"; override val title = "Pretty"; override val extension = "txt"
    override fun export(data: TableData, options: ExportOptions): Exported {
        val g = listOf(data.columns) + data.rows.map { r -> r.map(::cellText) }
        val w = data.columns.indices.map { c -> g.maxOf { it[c].length } }
        val line = w.joinToString("+", "+", "+") { "-".repeat(it + 2) }
        val rows = g.map { r -> r.mapIndexed { i, s -> " " + s.padEnd(w[i]) + " " }.joinToString("|", "|", "|") }
        return Exported.Text(buildString {
            appendLine(line); appendLine(rows.first()); appendLine(line)
            rows.drop(1).forEach { appendLine(it) }
            appendLine(line)
        })
    }
}

object PythonDataFrame : Extractor {
    override val id = "python-dataframe"; override val title = "Python DataFrame"; override val extension = "py"
    override fun export(data: TableData, options: ExportOptions): Exported {
        fun py(x: Any?): String = when (x) {
            null -> "None"
            is Boolean -> if (x) "True" else "False"
            is Number -> x.toString()
            else -> "'" + cellText(x).replace("\\", "\\\\").replace("'", "\\'") + "'"
        }
        val cols = data.columns.joinToString(", ") { "'$it'" }
        val rows = data.rows.joinToString(",\n    ") { r -> r.joinToString(", ", "[", "]") { py(it) } }
        return Exported.Text("import pandas as pd\n\ndf = pd.DataFrame([\n    $rows\n], columns=[$cols])\n")
    }
}

object XmlExtractor : Extractor {
    override val id = "xml"; override val title = "XML"; override val extension = "xml"
    override fun export(data: TableData, options: ExportOptions): Exported {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val rows = data.rows.joinToString("\n") { r ->
            "  <row>\n" + data.columns.zip(r).joinToString("\n") { (c, v) ->
                if (v == null) "    <$c/>" else "    <$c>${esc(cellText(v))}</$c>"
            } + "\n  </row>"
        }
        return Exported.Text("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<data>\n$rows\n</data>\n")
    }
}

object Xlsx : Extractor {
    override val id = "xlsx"; override val title = "Excel (xlsx)"; override val extension = "xlsx"
    override val supportsHeaders = true
    override fun export(data: TableData, options: ExportOptions): Exported {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(data.sourceTable?.substringAfterLast('.') ?: "data")
            var r = 0
            if (options.columnHeader) sheet.createRow(r++).let { row -> data.columns.forEachIndexed { i, c -> row.createCell(i).setCellValue(c) } }
            for (values in data.rows) {
                val row = sheet.createRow(r++)
                values.forEachIndexed { i, v ->
                    val cell = row.createCell(i)
                    when (v) {
                        null -> cell.setBlank()
                        is Number -> cell.setCellValue((v as? BigDecimal)?.toDouble() ?: v.toDouble())
                        is Boolean -> cell.setCellValue(v)
                        is TemporalAccessor, is java.util.Date -> cell.setCellValue(v.toString())
                        else -> cell.setCellValue(cellText(v))
                    }
                }
            }
            val out = ByteArrayOutputStream()
            wb.write(out)
            return Exported.Binary(out.toByteArray())
        }
    }
}
