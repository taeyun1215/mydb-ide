package dev.teo.mydb.plugin.table

import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.ModelIndex
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import dev.teo.mydb.core.edit.SubmitResult
import dev.teo.mydb.core.exec.ResultPage
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.transfer.CsvReader
import dev.teo.mydb.plugin.grid.EditTarget
import dev.teo.mydb.plugin.grid.GridFactory
import dev.teo.mydb.plugin.grid.GridToolbar
import dev.teo.mydb.plugin.grid.PageQuery
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.io.StringReader
import java.sql.DriverManager
import javax.swing.JComponent
import javax.swing.JPanel

/** "Data" tab of CSV and TSV files: the file as an editable table. */
class CsvTableEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = !file.isDirectory && file.extension?.lowercase() in setOf("csv", "tsv")
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = CsvTableEditor(project, file)
    override fun getEditorTypeId() = "mydb-csv-table"
    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

/**
 * The rows of the file live in an in-memory SQLite table (first row = column names, a hidden `__row`
 * keeps the order), so the result grid edits them with its usual pending changes. Submitting writes the
 * table back into the file's document; editing the text reloads the table.
 */
class CsvTableEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val separator = if (file.extension.equals("tsv", true)) '\t' else ','
    private val document: Document = FileDocumentManager.getInstance().getDocument(file)!!
    private val session = Session(DriverManager.getConnection("jdbc:sqlite::memory:"))
    private val panel = JPanel(BorderLayout())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var writing = false
    var grid: DataGrid? = null
        private set
    var columns: List<String> = emptyList()
        private set

    init {
        load()
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (writing) return
                alarm.cancelAllRequests()
                alarm.addRequest({ if (grid?.dataHookup?.mutator?.hasPendingChanges() != true) load() }, 300)
            }
        }, this)
    }

    /** Fills the table from the document and shows a new grid. */
    fun load() {
        val rows = CsvReader.read(StringReader(document.text), separator)
        val header = rows.firstOrNull().orEmpty()
        val names = mutableListOf<String>()
        header.forEachIndexed { i, h ->
            var n = h?.trim()?.ifEmpty { null } ?: "column_${i + 1}"
            var k = 2
            while (n in names || n == ROW) n = "${h ?: "column"}_${k++}"
            names += n
        }
        columns = names
        val q = { s: String -> "\"" + s.replace("\"", "\"\"") + "\"" }
        session.connection.createStatement().use { st ->
            st.execute("drop table if exists data")
            st.execute("create table data ($ROW integer primary key${names.joinToString("") { ", ${q(it)} text" }})")
        }
        if (names.isNotEmpty()) session.connection.prepareStatement("insert into data (${names.joinToString(", ") { q(it) }}) values (${names.joinToString(", ") { "?" }})").use { ps ->
            for (r in rows.drop(1)) { names.indices.forEach { i -> ps.setString(i + 1, r.getOrNull(i)) }; ps.addBatch() }
            ps.executeBatch()
        }
        val query = PageQuery { s, offset, limit ->
            when (val r = s.execute("select * from data order by $ROW limit $limit offset $offset", limit)) {
                is StatementResult.Rows -> r.page
                is StatementResult.Error -> throw java.sql.SQLException(r.message)
                else -> throw java.sql.SQLException("no rows")
            }
        }
        val page = query.load(session, 0, session.pageSize)
        grid?.let { panel.remove(GridToolbar.component(it)); Disposer.dispose(it) }
        val g = GridFactory.create(project, session, page, query, EditTarget("main", "data", listOf(ROW), setOf(ROW)), this)
        // the order column is bookkeeping, not data
        g.setColumnEnabled(ModelIndex.forColumn(g.getDataModel(DataAccessType.DATABASE_DATA), 0), false)
        (g.dataHookup as dev.teo.mydb.plugin.grid.JdbcGridHookUp).submitListeners += { if (it is SubmitResult.Ok) writeBack() }
        grid = g
        panel.add(GridToolbar.component(g), BorderLayout.CENTER)
        panel.revalidate(); panel.repaint()
    }

    /** The table as file text: the header row, then the rows in order. */
    fun tableText(): String {
        val page: ResultPage = (session.execute("select * from data order by $ROW", Int.MAX_VALUE - 1) as StatementResult.Rows).page
        fun cell(v: Any?) = v?.toString().orEmpty().let { t ->
            if (t.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        }
        val lines = listOf(columns.joinToString(separator.toString()) { cell(it) }) +
            page.rows.map { r -> r.drop(1).joinToString(separator.toString()) { cell(it) } }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun writeBack() {
        val text = tableText()
        writing = true
        try { WriteCommandAction.runWriteCommandAction(project, "Edit ${file.name}", null, { document.setText(text) }) } finally { writing = false }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = grid?.preferredFocusedComponent
    override fun getName() = "Data"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() { grid?.let(Disposer::dispose); runCatching { session.close() } }

    companion object { const val ROW = "__row" }
}
