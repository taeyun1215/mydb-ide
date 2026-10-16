package dev.teo.mydb.plugin.transfer

import com.intellij.openapi.ui.DialogWrapper
import dev.teo.mydb.core.transfer.DumpFormat
import dev.teo.mydb.plugin.MyDbTestCase
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** The pg_dump / restore dialogs and the tool runner (a fake tool stands in for pg_dump). */
class PgToolsDialogTest : MyDbTestCase() {
    fun `test dump dialog builds the command for the selected tables`() {
        val dialog = PgDumpDialog(project, dataSource, "obs", emptyList(), listOf("shop.orders"))
        try {
            dialog.tool.text = "/opt/pg/bin/pg_dump"
            dialog.format.selectedItem = DumpFormat.CUSTOM
            dialog.schemaOnly.isSelected = true
            val o = dialog.options()
            assertEquals(listOf("shop.orders"), o.tables)
            assertTrue(o.output, o.output.endsWith("shop_orders.dump"))
            assertTrue(o.schemaOnly)
            assertEquals("obs", o.target.database)
            assertEquals(dataSource.user, o.target.user)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun `test restore dialog uses psql for a sql file`() {
        val sql = Files.createTempFile("restore", ".sql")
        val dialog = PgRestoreDialog(project, dataSource, "obs")
        try {
            dialog.psqlTool.text = "/opt/pg/bin/psql"
            dialog.restoreTool.text = "/opt/pg/bin/pg_restore"
            dialog.input.text = sql.toString()
            assertEquals("/opt/pg/bin/psql", dialog.options().tool)
            dialog.input.text = "/tmp/archive.dump"
            assertEquals("/opt/pg/bin/pg_restore", dialog.options().tool)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE); Files.delete(sql) }
    }

    fun `test runner passes the password only in the environment`() {
        val dir = Files.createTempDirectory("fake-pg")
        val out = dir.resolve("args.txt")
        val tool = dir.resolve("pg_dump")
        Files.writeString(tool, "#!/bin/sh\necho \"$*\" > '$out'\n[ -n \"\$PGPASSWORD\" ] && echo has-password >> '$out'\nexit 0\n")
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"))
        try {
            val dialog = PgDumpDialog(project, dataSource, "obs", listOf("shop"), emptyList())
            val command = try { dialog.tool.text = tool.toString(); dev.teo.mydb.core.transfer.PgTools.dump(dialog.options()) } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
            val handler = PgToolRunner.start(project, "test", command, dataSource, show = false)
            assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(20)))
            val lines = Files.readAllLines(out)
            assertTrue(lines[0], lines[0].contains("--schema=shop") && lines[0].endsWith(" obs"))
            assertFalse("password not on the command line", lines[0].contains("password=") || lines[0].contains(" -W"))
            assertEquals("has-password", lines.getOrNull(1))
        } finally { dir.toFile().deleteRecursively() }
    }

    fun `test mysql runner passes MYSQL_PWD and feeds the script on standard input`() {
        val dir = Files.createTempDirectory("fake-mysql")
        val out = dir.resolve("seen.txt")
        val script = dir.resolve("restore.sql").also { Files.writeString(it, "select 1;\n") }
        val tool = dir.resolve("mysql")
        Files.writeString(tool, "#!/bin/sh\necho \"$*\" > '$out'\n[ -n \"\$MYSQL_PWD\" ] && echo has-password >> '$out'\ncat >> '$out'\n")
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"))
        val mysql = dev.teo.mydb.core.datasource.DataSourceConfig(id = "mysql-tools", name = "m", dbms = dev.teo.mydb.core.datasource.Dbms.MYSQL,
            url = "jdbc:mysql://db.local:3306/shop", user = "obs")
        dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).save(mysql)
        dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).setPassword(mysql, "secret")
        try {
            val cmd = dev.teo.mydb.core.transfer.MysqlTools.restore(tool.toString(), PgToolRunner.target(mysql, "shop"), "shop")
            val handler = PgToolRunner.start(project, "test", cmd, mysql, show = false, input = script.toFile())
            assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(20)))
            val lines = Files.readAllLines(out)
            assertEquals("--host=db.local --port=3306 --user=obs shop", lines[0])
            assertEquals("has-password", lines[1])
            assertEquals("select 1;", lines[2])
        } finally {
            dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).setPassword(mysql, null)
            dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).remove(mysql.id)
            dir.toFile().deleteRecursively()
        }
    }
}
