package dev.teo.mydb.core.transfer

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Command lines of pg_dump, pg_restore and psql. */
class PgToolsTest {
    private val target = PgTarget("db.local", 5433, "obs", "obs")

    @Test
    fun `dump of tables uses the table switches and never the password`() {
        val cmd = PgTools.dump(DumpOptions("/usr/bin/pg_dump", target, "/tmp/out.sql", tables = listOf("shop.orders", "shop.customers"),
            schemas = listOf("ignored"), schemaOnly = true, noOwner = true, extraArgs = "--lock-wait-timeout=5 --exclude-table-data='shop.big table'"))
        assertEquals(listOf("/usr/bin/pg_dump", "--host=db.local", "--port=5433", "--username=obs", "--no-password", "--format=p", "--schema-only",
            "--no-owner", "--table=shop.orders", "--table=shop.customers", "--file=/tmp/out.sql", "--lock-wait-timeout=5",
            "--exclude-table-data=shop.big table", "obs"), cmd)
    }

    @Test
    fun `dump of a schema as custom archive`() {
        val cmd = PgTools.dump(DumpOptions("pg_dump", target.copy(port = null, user = null), "/tmp/shop.dump", DumpFormat.CUSTOM, schemas = listOf("shop")))
        assertEquals(listOf("pg_dump", "--host=db.local", "--no-password", "--format=c", "--schema=shop", "--file=/tmp/shop.dump", "obs"), cmd)
    }

    @Test
    fun `restore picks psql for sql scripts and pg_restore for archives`() {
        val sql = Files.createTempFile("dump", ".sql")
        try {
            assertTrue(PgTools.isPlainSql(sql.toString()))
            assertEquals(listOf("psql", "--host=db.local", "--port=5433", "--username=obs", "--no-password", "--dbname=obs", "--single-transaction",
                "--set=ON_ERROR_STOP=1", "--file=$sql"), PgTools.restore(RestoreOptions("psql", target, sql.toString())))
        } finally { Files.delete(sql) }
        assertFalse(PgTools.isPlainSql("/tmp/missing.dump"))
        assertEquals(listOf("pg_restore", "--host=db.local", "--port=5433", "--username=obs", "--no-password", "--dbname=obs", "--clean", "--if-exists",
            "/tmp/x.dump"), PgTools.restore(RestoreOptions("pg_restore", target, "/tmp/x.dump", clean = true, ifExists = true, singleTransaction = false)))
    }

    @Test
    fun `extra arguments split like a shell`() {
        assertEquals(listOf("-a", "b c", "", "d"), PgTools.splitArgs(" -a \"b c\" '' d "))
    }

    @Test
    fun `mysqldump and mysql command lines`() {
        val t = PgTarget("db.local", 3307, "obs", "shop")
        assertEquals(listOf("mysqldump", "--host=db.local", "--port=3307", "--user=obs", "--no-data", "--add-drop-table", "--result-file=/tmp/shop.sql", "shop", "orders"),
            MysqlTools.dump(MysqlDumpOptions("mysqldump", t, "/tmp/shop.sql", "shop", listOf("orders"), noData = true)))
        assertEquals(listOf("mysql", "--host=db.local", "--port=3307", "--user=obs", "shop"), MysqlTools.restore("mysql", t, "shop"))
    }
}
