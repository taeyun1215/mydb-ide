package dev.teo.mydb.plugin.transfer

import com.intellij.openapi.project.Project
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote
import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.core.transfer.CsvReader
import dev.teo.mydb.core.transfer.ExportOptions
import dev.teo.mydb.core.transfer.Exported
import dev.teo.mydb.core.transfer.Extractor
import dev.teo.mydb.core.transfer.ImportOptions
import dev.teo.mydb.core.transfer.ImportPlan
import dev.teo.mydb.core.transfer.ImportResult
import dev.teo.mydb.core.transfer.Importer
import dev.teo.mydb.core.transfer.TableData
import dev.teo.mydb.plugin.console.OutputLog
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import java.nio.file.Files
import java.nio.file.Path

/** Export logic behind the "Export Data" dialog. UI-free so it can be tested. */
object DataExport {
    const val PREVIEW_ROWS = 10

    /** Every row of a table, read on its own session. Blocking: call off the EDT. */
    fun loadTable(project: Project, config: DataSourceConfig, schema: String, table: String): TableData {
        val password = DataSourceStorage.getInstance(project).password(config.id)
        return Session(dev.teo.mydb.plugin.datasource.DbConnector.get(project).open(config, password, SessionPurpose.IMPORT)).use { s ->
            when (val r = s.execute("SELECT * FROM ${dev.teo.mydb.core.exec.SqlDialect.of(config.dbms).qualified(schema, table)}", Int.MAX_VALUE - 1)) {
                is StatementResult.Rows -> fromPage(r.page, "$schema.$table")
                is StatementResult.Error -> throw java.sql.SQLException(r.message, r.sqlState)
                is StatementResult.Update -> TableData(emptyList(), emptyList(), "$schema.$table")
            }
        }
    }

    fun fromPage(page: ResultPage, source: String?) =
        TableData(page.columns.map { it.label }, page.rows.map { it.toList() }, source)

    fun preview(extractor: Extractor, data: TableData, options: ExportOptions): String =
        when (val out = extractor.export(data.copy(rows = data.rows.take(PREVIEW_ROWS)), options)) {
            is Exported.Text -> out.text
            is Exported.Binary -> "Binary ${extractor.title} file (${out.bytes.size} bytes for the preview rows)"
        }

    /** `~/<table>.<extension>`. */
    fun defaultPath(name: String, extractor: Extractor): Path =
        Path.of(System.getProperty("user.home"), "${name.substringAfterLast('.')}.${extractor.extension}")

    /** Usable output path: its parent exists and is writable. */
    fun isWritableTarget(path: Path?) = path?.parent?.let { Files.isDirectory(it) && Files.isWritable(it) } == true

    fun write(path: Path, extractor: Extractor, data: TableData, options: ExportOptions) {
        when (val out = extractor.export(data, options)) {
            is Exported.Text -> Files.writeString(path, out.text)
            is Exported.Binary -> Files.write(path, out.bytes)
        }
    }

    fun text(extractor: Extractor, data: TableData, options: ExportOptions): String? =
        (extractor.export(data, options) as? Exported.Text)?.text
}

/** Import logic behind the "Import" dialog. */
object DataImport {
    /** First lines of the file parsed as the importer will. */
    fun previewRows(file: Path, options: ImportOptions, limit: Int = 50): List<List<String?>> =
        Files.newBufferedReader(file).use { CsvReader.read(it, options.separator) }.take(limit + 1)

    /** New table named after the file, or the existing [table] with columns mapped by position. */
    fun plan(file: Path, schema: String, table: DbTable?, options: ImportOptions): ImportPlan = Importer.planFor(file, schema, table?.let { t ->
        t.name to t.columns.map { it.name to it.type }
    }, options)

    /**
     * Runs the import on its own session, then introspects only the target table so the explorer
     * shows it without a full refresh. Blocking: call off the EDT.
     */
    fun run(project: Project, config: DataSourceConfig, database: String, plan: ImportPlan, file: Path, options: ImportOptions): ImportResult {
        val password = DataSourceStorage.getInstance(project).password(config.id)
        val log = OutputLog.getInstance(project)
        val title = "import ${plan.table}"
        val result = Session(dev.teo.mydb.plugin.datasource.DbConnector.get(project).open(config, password, SessionPurpose.IMPORT)).use { s ->
            if (plan.createTable) log.statement(title, "$database.${plan.schema}", plan.ddl().replace('\n', ' '))
            Importer(s).run(plan, file, options)
        }
        log.info(config, title, "${result.inserted} rows imported into ${plan.schema}.${plan.table}" +
            if (result.failed > 0) ", ${result.failed} failed" else "")
        ModelService.getInstance(project).refreshNow(config, RefreshTarget.Tables(database, plan.schema, setOf(plan.table)))
        return result
    }
}
