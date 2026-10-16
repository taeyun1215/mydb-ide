package dev.teo.mydb.plugin.transfer

import com.intellij.openapi.ui.DialogWrapper
import dev.teo.mydb.core.transfer.Extractors
import dev.teo.mydb.core.transfer.ImportOptions
import dev.teo.mydb.plugin.MyDbTestCase
import dev.teo.mydb.plugin.TestDatabase
import dev.teo.mydb.plugin.model.ModelService
import java.nio.file.Files
import java.nio.file.Path

/** Export and import through the plugin layer: */
class TransferTest : MyDbTestCase() {
    /** The sample file `testdb/expected/import.csv`. */
    private val importSource: Path by lazy {
        val observed = Path.of(System.getProperty("mydb.seedDir"), "../expected/import.csv").normalize()
        Files.createTempDirectory("mydb-import").resolve("import.csv").also { Files.copy(observed, it) }
    }

    fun `test export of a whole table as CSV with header`() {
        val data = DataExport.loadTable(project, dataSource, "shop", "products")
        assertEquals("shop.products", data.sourceTable)
        assertEquals(20, data.rows.size)
        val csv = DataExport.text(Extractors.byId("csv"), data, dev.teo.mydb.core.transfer.ExportOptions(columnHeader = true))!!
        val lines = csv.lines()
        assertEquals("id,sku,title,price", lines[0])
        assertEquals("1,SKU-1,product 1,1.25", lines[1])
        assertEquals("2,SKU-2,product 2,2.50", lines[2])
    }

    fun `test export dialog defaults, preview and output path`() {
        val data = DataExport.loadTable(project, dataSource, "shop", "products")
        val dialog = ExportDialog(project, data)
        try {
            assertEquals("csv", dialog.selectedExtractor().id)
            assertEquals(Path.of(System.getProperty("user.home"), "products.csv"), dialog.outputPath())
            assertTrue(dialog.isOKActionEnabled)
            val preview = DataExport.preview(dialog.selectedExtractor(), data, dialog.options())
            assertEquals(DataExport.PREVIEW_ROWS, preview.trimEnd().lines().size)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun `test import into a new table appears in the model without a full refresh`() {
        assertTrue(importSource.toString(), Files.exists(importSource))
        val config = freshDataSource("import-ds")
        val database = config.url.substringAfterLast('/')
        val modelBefore = ModelService.getInstance(project).model(config.id)!!
        val plan = DataImport.plan(importSource, "shop", null, ImportOptions())
        assertEquals(listOf("code" to "text", "name" to "text", "amount" to "double precision", "created" to "text"), plan.columns.map { it.name to it.type })

        val result = DataImport.run(project, config, database, plan, importSource, ImportOptions())
        assertEquals(3, result.inserted)
        val model = ModelService.getInstance(project).model(config.id)!!
        val table = model.schema(database, "shop")?.table("import")
        assertNotNull("imported table must be in the model", table)
        assertEquals(listOf("code", "name", "amount", "created"), table!!.columns.map { it.name })
        // only the new table was introspected: the other tables are the same objects as before
        assertSame(modelBefore.schema(database, "shop")!!.table("products"), model.schema(database, "shop")!!.table("products"))
        assertEquals(3, TestDatabase.query(config, "select count(*) from shop.import") { it.getInt(1) })
    }

    fun `test import into an existing table with unconvertible values as NULL`() {
        val config = freshDataSource("import-existing-ds")
        val database = config.url.substringAfterLast('/')
        TestDatabase.query(config, "select 1") { }
        java.sql.DriverManager.getConnection(config.url, "obs", "obs").use {
            it.createStatement().execute("create table shop.amounts(code text, amount numeric)")
        }
        ModelService.getInstance(project).refreshNow(config, dev.teo.mydb.core.introspect.RefreshTarget.Tables(database, "shop", setOf("amounts")))
        val csv = Files.createTempFile("amounts", ".csv")
        Files.writeString(csv, "code,amount\nA,1.5\nB,not a number\nC,3\n")
        val target = ModelService.getInstance(project).model(config.id)!!.schema(database, "shop")!!.table("amounts")!!
        val plan = DataImport.plan(csv, "shop", target, ImportOptions())
        assertFalse(plan.createTable)

        val result = DataImport.run(project, config, database, plan, csv, ImportOptions(insertUnconvertibleAsNull = true))
        assertEquals(3, result.inserted)
        assertEquals(1, TestDatabase.query(config, "select count(*) from shop.amounts where amount is null") { it.getInt(1) })
    }

    fun `test import dialog proposes a new table from the file and the existing table from the explorer`() {
        val fromSchema = ImportDialog(project, dataSource, "obs", "shop", importSource, null)
        try {
            val plan = fromSchema.plan()
            assertTrue(plan.createTable)
            assertEquals("import", plan.table)
            assertEquals(listOf("code", "name", "amount", "created"), plan.columns.map { it.name })
            assertTrue(fromSchema.options().errorLog != null)
            assertFalse(fromSchema.options().insertUnconvertibleAsNull)
        } finally { fromSchema.close(DialogWrapper.CANCEL_EXIT_CODE) }

        val products = TestDatabase.model.schema("obs", "shop")!!.table("products")!!
        val fromTable = ImportDialog(project, dataSource, "obs", "shop", importSource, products)
        try {
            val plan = fromTable.plan()
            assertFalse(plan.createTable)
            assertEquals("products", plan.table)
            assertEquals(listOf("id", "sku", "title", "price"), plan.columns.map { it.name })
        } finally { fromTable.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }
}
