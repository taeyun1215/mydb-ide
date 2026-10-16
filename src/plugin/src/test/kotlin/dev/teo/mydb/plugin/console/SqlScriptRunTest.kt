package dev.teo.mydb.plugin.console

import com.intellij.execution.RunManager
import dev.teo.mydb.plugin.MyDbTestCase
import java.nio.file.Files

/** The SQL Script run configuration. */
class SqlScriptRunTest : MyDbTestCase() {
    private fun run(sql: String): SqlScriptProcess {
        val f = Files.createTempFile("script", ".sql").also { Files.writeString(it, sql) }
        val settings = RunManager.getInstance(project).createConfiguration("script", SqlScriptConfigurationType.getInstance().configurationFactories.single())
        val c = settings.configuration as SqlScriptRunConfiguration
        c.options.dataSourceId = dataSource.id; c.options.scriptPath = f.toString(); c.options.schema = "shop"
        c.checkConfiguration()
        val result = c.execute()
        val p = result.processHandler as SqlScriptProcess
        waitFor("script finished") { p.finishedWith != null }
        com.intellij.openapi.util.Disposer.dispose(result.executionConsole)
        Files.delete(f)
        return p
    }

    fun `test script runs statement by statement and stops at an error`() {
        val ok = run("select count(*) from orders;\nselect 1;")
        assertEquals(ok.transcript.toString(), 0, ok.finishedWith)
        assertTrue(ok.transcript.toString(), ok.transcript.contains("> select count(*) from orders") && ok.transcript.contains("1 row(s)"))
        val bad = run("select 1;\nselect * from missing_table;\nselect 2;")
        assertEquals(1, bad.finishedWith)
        assertTrue(bad.transcript.toString(), bad.transcript.contains("[42P01]"))
        assertFalse("stopped at the error", bad.transcript.contains("> select 2"))
    }
}
