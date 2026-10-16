package dev.teo.mydb.core.history

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Per data source history of statements run from consoles.
 * Stored as a plain SQL file: one statement per entry, separated by `;\n`, newest last.
 * Grid DML is not recorded.
 */
class QueryHistory(private val dir: Path, private val limit: Int = 1000) {

    fun add(dataSourceId: String, statement: String) {
        val s = statement.trim().removeSuffix(";").trim()
        if (s.isEmpty()) return
        val entries = list(dataSourceId).filter { it != s } + s
        Files.createDirectories(dir)
        Files.writeString(file(dataSourceId), entries.takeLast(limit).joinToString("") { "$it;\n" },
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    fun list(dataSourceId: String): List<String> {
        val f = file(dataSourceId)
        if (!Files.exists(f)) return emptyList()
        return dev.teo.mydb.core.exec.StatementSplitter.split(Files.readString(f)).map { it.text }
    }

    fun remove(dataSourceId: String, statement: String) {
        val entries = list(dataSourceId).filter { it != statement }
        Files.writeString(file(dataSourceId), entries.joinToString("") { "$it;\n" })
    }

    private fun file(id: String) = dir.resolve("$id.sql")
}
