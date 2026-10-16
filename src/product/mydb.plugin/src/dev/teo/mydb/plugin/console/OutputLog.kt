package dev.teo.mydb.plugin.console

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.teo.mydb.core.datasource.DataSourceConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session output log: timestamped connection events, the executed
 * statement prefixed with `db.schema>`, row counts with execution/fetch times and errors in red.
 */
@Service(Service.Level.PROJECT)
class OutputLog(private val project: Project) : Disposable {
    private val consoles = ConcurrentHashMap<String, ConsoleView>()
    private val transcripts = ConcurrentHashMap<String, MutableList<String>>()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS")

    fun console(sessionTitle: String): ConsoleView = consoles.getOrPut(sessionTitle) {
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { Disposer.register(this, it) }
    }

    private fun print(title: String, text: String, type: ConsoleViewContentType) {
        val line = "[${LocalDateTime.now().format(stamp)}] $text\n"
        transcripts.computeIfAbsent(title) { java.util.Collections.synchronizedList(mutableListOf()) }.add(text)
        ApplicationManager.getApplication().invokeLater({ console(title).print(line, type) }, project.disposed)
    }

    fun info(config: DataSourceConfig, title: String, text: String) = print(title, text, ConsoleViewContentType.NORMAL_OUTPUT)
    fun statement(title: String, prompt: String, sql: String) = print(title, "$prompt> $sql", ConsoleViewContentType.USER_INPUT)
    fun rows(title: String, count: Int, from: Int, total: Long, exec: Long, fetch: Long) =
        print(title, "Fetched $count rows from row ${from + 1} in $total ms (run $exec ms, fetch $fetch ms)", ConsoleViewContentType.NORMAL_OUTPUT)
    fun updated(title: String, count: Long, millis: Long) =
        print(title, "$count ${if (count == 1L) "row" else "rows"} affected in $millis ms", ConsoleViewContentType.NORMAL_OUTPUT)
    fun error(title: String, text: String) = print(title, text, ConsoleViewContentType.ERROR_OUTPUT)

    /** Log of a grid bound to the session [title] (submitted DML and its outcome). */
    fun gridLog(title: String) = dev.teo.mydb.plugin.grid.GridLog { text, isError -> if (isError) error(title, text) else print(title, text, ConsoleViewContentType.NORMAL_OUTPUT) }

    /** Disposes the output console of a closed session. */
    fun release(sessionTitle: String) {
        transcripts.remove(sessionTitle)
        consoles.remove(sessionTitle)?.let { c -> ApplicationManager.getApplication().invokeLater({ Disposer.dispose(c) }, project.disposed) }
    }

    /** Messages logged for a session, without timestamps (most recent last). */
    fun transcript(sessionTitle: String): List<String> = transcripts[sessionTitle]?.toList().orEmpty()

    override fun dispose() { consoles.clear(); transcripts.clear() }

    companion object { fun getInstance(project: Project): OutputLog = project.service() }
}
