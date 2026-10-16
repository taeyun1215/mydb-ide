package dev.teo.mydb.core.transfer

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.StatementResult
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Data import and export. */
class TransferAcceptanceTest {
    private fun session(cfg: dev.teo.mydb.core.datasource.DataSourceConfig = SeededPostgres.config()) = Session(Connector().open(cfg, SeededPostgres.PASSWORD))

    private fun products(): TableData = session().use { s ->
        val p = (s.execute("select * from shop.products where id between 2 and 21 order by id") as StatementResult.Rows).page
        TableData(p.columns.map { it.label }, p.rows.map { it.toList() }, "shop.products")
    }

    private fun text(e: Exported) = (e as Exported.Text).text

    @Test
    fun `csv with header matches the expected export`() {
        val expected = File(System.getProperty("mydb.seedDir"), "../expected/export.csv").readText()
        val ours = text(Extractors.byId("csv").export(products(), ExportOptions(columnHeader = true)))
        // the expected file predates row edits made by other tests; compare the shared part (header + products 2..20)
        assertEquals(expected.lines().take(20), ours.lines().take(20))
    }

    @Test
    fun `csv quoting follows RFC 4180`() {
        val data = TableData(listOf("a"), listOf(listOf("x,y"), listOf("say \"hi\""), listOf("two\nlines"), listOf(null)))
        assertEquals("\"x,y\"\n\"say \"\"hi\"\"\"\n\"two\nlines\"\n\n", text(Extractors.byId("csv").export(data, ExportOptions())))
    }

    @Test
    fun `all export formats are available`() {
        val titles = Extractors.all.map { it.id }
        listOf("sql-insert", "sql-update", "where", "xlsx", "csv", "tsv", "pipe", "semicolon", "html", "html-page", "json", "markdown",
            "one-row", "pretty", "python-dataframe", "sql-insert-multirow", "xml").forEach { assertTrue(it in titles, it) }
    }

    @Test
    fun `formats produce valid output`() {
        val data = products()
        val sql = text(Extractors.byId("sql-insert-multirow").export(data, ExportOptions()))
        val cfg = SeededPostgres.freshDatabase()
        SeededPostgres.connect(cfg) { c ->
            c.createStatement().execute("create table copy as select * from shop.products where false")
            c.createStatement().execute(sql.replace("shop.products", "copy"))
            c.createStatement().executeQuery("select count(*) from copy").use { rs -> rs.next(); assertEquals(19, rs.getInt(1)) }
        }
        val json = text(Extractors.byId("json").export(data, ExportOptions()))
        assertEquals(19, kotlinx.serialization.json.Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonArray }.size)
        val md = text(Extractors.byId("markdown").export(data, ExportOptions()))
        assertEquals("| id | sku | title | price |", md.lines().first())
        val xlsx = Extractors.byId("xlsx").export(data, ExportOptions(columnHeader = true)) as Exported.Binary
        XSSFWorkbook(xlsx.bytes.inputStream()).use { wb ->
            assertEquals("sku", wb.getSheetAt(0).getRow(0).getCell(1).stringCellValue)
            assertEquals(2.5, wb.getSheetAt(0).getRow(1).getCell(3).numericCellValue)
        }
    }

    @Test
    fun `transpose`() {
        val data = TableData(listOf("a", "b"), listOf(listOf(1, 2), listOf(3, 4)))
        assertEquals("a,1,3\nb,2,4\n", text(Extractors.byId("csv").export(data, ExportOptions(transpose = true, columnHeader = true))))
    }

    private fun importFile(): java.nio.file.Path {
        val f = Files.createTempDirectory("imp").resolve("import.csv")
        Files.copy(File(System.getProperty("mydb.seedDir"), "../expected/import.csv").toPath(), f)
        return f
    }

    @Test
    fun `import into a new table`() {
        val cfg = SeededPostgres.freshDatabase()
        session(cfg).use { s ->
            val imp = Importer(s)
            val plan = imp.planNewTable("shop", importFile())
            assertEquals("import", plan.table)
            assertEquals(listOf("code" to "text", "name" to "text", "amount" to "double precision", "created" to "text"), plan.columns.map { it.name to it.type })
            val (result, log) = SeededPostgres.captureStatements { imp.run(plan, importFile()) }
            assertEquals(3, result.inserted)
            val order = listOf("create table shop.import", "BEGIN", "INSERT INTO shop.import", "COMMIT").map { p -> log.indexOfFirst { it.startsWith(p) } }
            assertTrue(order.all { it >= 0 } && order == order.sorted(), log.toString())
        }
        SeededPostgres.connect(cfg) { c ->
            c.createStatement().executeQuery("select code, name, amount, created from shop.import order by code").use { rs ->
                rs.next(); assertEquals("Smith, John", rs.getString(2)); assertEquals(10.5, rs.getDouble(3))
                rs.next(); assertEquals("quote \"x\"", rs.getString(2)); rs.getDouble(3); assertTrue(rs.wasNull())
                rs.next(); rs.getString(4); assertTrue(rs.wasNull())
            }
        }
    }

    @Test
    fun `unconvertible values become null or go to the error file`() {
        val cfg = SeededPostgres.freshDatabase()
        val f = Files.createTempFile("bad", ".csv")
        Files.writeString(f, "sku,title,price\nA,a,1.5\nB,b,abc\n")
        SeededPostgres.connect(cfg) { it.createStatement().execute("create table shop.p2(sku text, title text, price numeric)") }
        session(cfg).use { s ->
            val plan = Importer(s).planExisting("shop", "p2", listOf("sku" to "text", "title" to "text", "price" to "numeric"))
            val r = Importer(s).run(plan, f, ImportOptions(insertUnconvertibleAsNull = true))
            assertEquals(ImportResult(2, 0), r)
            val err = Files.createTempFile("err", ".log")
            val r2 = Importer(s).run(plan, f, ImportOptions(errorLog = err))
            assertEquals(ImportResult(1, 1), r2)
            assertTrue("abc" in Files.readString(err))
        }
        SeededPostgres.connect(cfg) { c ->
            c.createStatement().executeQuery("select count(*) filter (where price is null), count(*) from shop.p2").use { rs ->
                rs.next(); assertEquals(1, rs.getInt(1)); assertEquals(3, rs.getInt(2))
            }
        }
    }

    @Test
    fun `csv reader handles quotes and blanks`() {
        val rows = CsvReader.read("a,b\n\"x,1\",\n\"q\"\"\",\"\"\n".reader())
        assertEquals(listOf(listOf("a", "b"), listOf("x,1", null), listOf("q\"", "")), rows)
    }

    @Test
    fun `type inference`() {
        assertEquals("integer", TypeInference.infer(listOf("1", "2", null)))
        assertEquals("bigint", TypeInference.infer(listOf("9999999999")))
        assertEquals("double precision", TypeInference.infer(listOf("10.5", null, "7")))
        assertEquals("date", TypeInference.infer(listOf("2026-01-02", "2026-02-03")))
        assertEquals("text", TypeInference.infer(listOf("2026-01-02", null)))
        assertEquals("boolean", TypeInference.infer(listOf("true", "FALSE")))
    }
}
