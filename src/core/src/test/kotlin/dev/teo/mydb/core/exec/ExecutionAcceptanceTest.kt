package dev.teo.mydb.core.exec

import dev.teo.mydb.core.SeededPostgres
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.datasource.IsolationLevel
import dev.teo.mydb.core.datasource.TxMode
import dev.teo.mydb.core.edit.DefaultValue
import dev.teo.mydb.core.edit.GridEdits
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.core.edit.UserText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Statement execution, results, editing and transactions. */
class ExecutionAcceptanceTest {
    private fun session(cfg: DataSourceConfig, mode: TxMode = TxMode.AUTO) = Session(Connector().open(cfg, SeededPostgres.PASSWORD), mode)
    private fun scalar(cfg: DataSourceConfig, sql: String): Any? =
        SeededPostgres.connect(cfg) { c -> c.createStatement().executeQuery(sql).use { rs -> rs.next(); rs.getObject(1) } }

    // ---- run targets ----

    private val twoStatements = "select * from shop.customers where id < 30;\nselect sku, title, price from shop.products order by price desc;"

    @Test
    fun `caret after the first semicolon asks between statement and script`() {
        val targets = RunTargets.resolve(twoStatements, caret = twoStatements.indexOf(';') + 1)
        assertEquals(2, targets.size)
        assertEquals("select * from shop.customers where id < 30", (targets[0] as RunTarget.Statement).statement.text)
        assertEquals(2, (targets[1] as RunTarget.Script).statements.size)
    }

    @Test
    fun `caret inside a statement runs it directly`() {
        val targets = RunTargets.resolve(twoStatements, caret = 10)
        assertEquals(1, targets.size)
        assertIs<RunTarget.Statement>(targets.single())
    }

    @Test
    fun `single statement never asks`() {
        assertEquals(1, RunTargets.resolve("select 1;", caret = 9).size)
    }

    @Test
    fun `splitter respects quotes, comments and dollar quoting`() {
        val sql = "select ';' as a; -- c;\nselect \$\$ ; \$\$; /* ; */ select 3"
        assertEquals(listOf("select ';' as a", "-- c;\nselect \$\$ ; \$\$", "/* ; */ select 3"), StatementSplitter.split(sql).map { it.text })
    }

    // ---- results ----

    @Test
    fun `join query result`() {
        session(SeededPostgres.config()).use { s ->
            val r = s.execute("select * from shop.orders o join shop.customers c on o.customer_id = c.id where o.id <= 20")
            assertIs<StatementResult.Rows>(r)
            assertEquals(20, r.page.rows.size)
            assertEquals(listOf("id", "customer_id", "status", "ordered_at", "id", "name", "email", "created_at"), r.page.columns.map { it.label })
            assertNull(r.page.sourceTable, "join results are read-only")
        }
    }

    @Test
    fun `single table result is editable and named after its table`() {
        session(SeededPostgres.config()).use { s ->
            val r = s.execute("select * from shop.orders where id <= 20") as StatementResult.Rows
            assertEquals("shop" to "orders", r.page.sourceTable)
        }
    }

    @Test
    fun `first page fetches page size plus one`() {
        session(SeededPostgres.config()).use { s ->
            val r = s.execute("select * from shop.orders") as StatementResult.Rows // 200 rows
            assertFalse(r.page.hasMore)
            s.pageSize = 50
            val p = s.execute("select * from shop.orders") as StatementResult.Rows
            assertEquals(50, p.page.rows.size)
            assertTrue(p.page.hasMore)
        }
    }

    @Test
    fun `table editor query`() {
        assertEquals("SELECT t.*\nFROM shop.orders t\nLIMIT 501", TableQuery.select("shop", "orders", pageSize = 500))
        assertEquals("SELECT t.*\nFROM shop.orders t\nWHERE status = 'paid'\nORDER BY id desc\nLIMIT 11",
            TableQuery.select("shop", "orders", where = "status = 'paid'", orderBy = "id desc", pageSize = 10))
    }

    // ---- grid editing ----

    private fun products(s: Session) = s.execute("select * from shop.products order by id") as StatementResult.Rows

    @Test
    fun `cell edit is pending until submitted in one transaction`() {
        val cfg = SeededPostgres.freshDatabase()
        session(cfg).use { s ->
            val page = (s.execute("select * from shop.orders where id <= 20 order by id") as StatementResult.Rows).page
            val edits = GridEdits("shop", "orders", page, keyColumns = listOf("id"))
            edits.setValue(2, 2, UserText("paid")) // id = 3, status column
            assertEquals("new", scalar(cfg, "select status from shop.orders where id = 3"))
            val (result, log) = SeededPostgres.captureStatements { edits.submit(s) }
            assertIs<SubmitResult.Ok>(result)
            assertEquals("paid", scalar(cfg, "select status from shop.orders where id = 3"))
            val i = log.indexOf("BEGIN")
            assertTrue(i >= 0, log.toString())
            assertEquals("UPDATE shop.orders SET status = \$1 WHERE id = \$2", log[i + 1])
            assertEquals("COMMIT", log[i + 2])
        }
    }

    @Test
    fun `new row defaults and insert preview`() {
        session(SeededPostgres.config()).use { s ->
            val edits = GridEdits("shop", "products", products(s).page, listOf("id"), columnsWithDefault = setOf("id"))
            val row = edits.addRow()
            assertEquals(listOf<Any?>(DefaultValue, null, null, null), edits.inserts[row].toList())
            edits.setInsertedValue(row, 1, UserText("SKU-21"))
            edits.setInsertedValue(row, 2, UserText("product 21"))
            edits.setInsertedValue(row, 3, UserText("26.25"))
            assertEquals("INSERT INTO shop.products (id, sku, title, price)\nVALUES (DEFAULT, 'SKU-21', 'product 21', 26.25);", edits.previewText())
        }
    }

    @Test
    fun `deletes come before inserts`() {
        session(SeededPostgres.config()).use { s ->
            val edits = GridEdits("shop", "products", products(s).page, listOf("id"), setOf("id"))
            val row = edits.addRow()
            edits.setInsertedValue(row, 1, UserText("SKU-21"))
            edits.deleteRow(7) // id = 8
            assertEquals("DELETE\nFROM shop.products\nWHERE id = 8;", edits.previewText().substringBefore("\n\n"))
        }
    }

    @Test
    fun `failing statement rolls back everything and keeps pending changes`() {
        val cfg = SeededPostgres.freshDatabase()
        session(cfg).use { s ->
            val edits = GridEdits("shop", "products", products(s).page, listOf("id"), setOf("id"))
            edits.deleteRow(7) // referenced by order_items → FK violation
            val row = edits.addRow()
            edits.setInsertedValue(row, 1, UserText("SKU-21")); edits.setInsertedValue(row, 2, UserText("p")); edits.setInsertedValue(row, 3, UserText("1"))
            val r = edits.submit(s)
            assertIs<SubmitResult.Failed>(r)
            assertEquals("23503", r.sqlState)
            assertTrue(edits.hasChanges)
            assertEquals(20L, scalar(cfg, "select count(*) from shop.products"))
        }
    }

    @Test
    fun `table without key uses all columns and rejects multi-row updates`() {
        val cfg = SeededPostgres.freshDatabase()
        SeededPostgres.connect(cfg) { it.createStatement().execute("create table shop.nopk(a int, b text); insert into shop.nopk values (1,'x'),(2,'y'),(2,'y')") }
        session(cfg).use { s ->
            val page = (s.execute("select * from shop.nopk order by a") as StatementResult.Rows).page
            val edits = GridEdits("shop", "nopk", page, keyColumns = emptyList())
            edits.setValue(1, 1, UserText("z"))
            assertEquals("UPDATE shop.nopk\nSET b = 'z'\nWHERE a = 2\n  AND b = 'y';", edits.previewText())
            val r = edits.submit(s)
            assertIs<SubmitResult.Failed>(r)
            assertEquals("Unexpected update count received (actual: 2, expected: 1)", r.message)
            assertEquals(0L, scalar(cfg, "select count(*) from shop.nopk where b = 'z'"))
        }
    }

    @Test
    fun `nothing reaches the server before submit and revert clears`() {
        session(SeededPostgres.config()).use { s ->
            val edits = GridEdits("shop", "products", products(s).page, listOf("id"))
            val (_, log) = SeededPostgres.captureStatements { edits.setValue(0, 2, UserText("changed")); edits.deleteRow(3) }
            assertTrue(log.none { it.startsWith("UPDATE") || it.startsWith("DELETE") })
            edits.revertAll()
            assertFalse(edits.hasChanges)
        }
    }

    @Test
    fun `null original values are matched with IS NULL`() {
        session(SeededPostgres.config()).use { s ->
            val page = (s.execute("select 1 as a, null::text as b") as StatementResult.Rows).page
            val e = GridEdits("shop", "x", page, emptyList())
            e.deleteRow(0)
            assertTrue("WHERE a = 1\n  AND b IS NULL;" in e.previewText())
        }
    }

    // ---- transactions ----

    @Test
    fun `manual mode commit and rollback`() {
        val cfg = SeededPostgres.freshDatabase()
        session(cfg).use { s ->
            val (_, log) = SeededPostgres.captureStatements { s.txMode = TxMode.MANUAL }
            assertTrue(log.isEmpty(), log.toString())
            assertFalse(s.transactionOpen)
            s.execute("update shop.products set price = price + 100 where id = 1")
            assertTrue(s.transactionOpen)
            assertEquals("1.25", scalar(cfg, "select price::text from shop.products where id = 1"))
            s.rollback()
            assertFalse(s.transactionOpen)
            assertEquals("1.25", scalar(cfg, "select price::text from shop.products where id = 1"))
            s.execute("update shop.products set price = price + 100 where id = 1")
            s.commit()
            assertEquals("101.25", scalar(cfg, "select price::text from shop.products where id = 1"))
        }
    }

    @Test
    fun `isolation level applies to the next transaction`() {
        session(SeededPostgres.config(), TxMode.MANUAL).use { s ->
            s.isolation = IsolationLevel.SERIALIZABLE
            val r = s.execute("show transaction isolation level") as StatementResult.Rows
            assertEquals("serializable", r.page.rows.single()[0])
            s.rollback()
        }
    }

    @Test
    fun `console and table editor use separate server sessions`() {
        val cfg = SeededPostgres.config()
        session(cfg).use { a -> session(cfg).use { b ->
            val pa = (a.execute("select pg_backend_pid()") as StatementResult.Rows).page.rows.single()[0]
            val pb = (b.execute("select pg_backend_pid()") as StatementResult.Rows).page.rows.single()[0]
            assertTrue(pa != pb)
        } }
    }

    @Test
    fun `errors carry sql state and position`() {
        session(SeededPostgres.config()).use { s ->
            val r = s.execute("select statsu from shop.orders")
            assertIs<StatementResult.Error>(r)
            assertEquals("42703", r.sqlState)
            assertNotNull(r.position)
        }
    }
}
