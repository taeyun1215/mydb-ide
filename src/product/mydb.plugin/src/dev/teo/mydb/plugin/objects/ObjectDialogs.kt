package dev.teo.mydb.plugin.objects

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.ddl.PgObjectDdl
import dev.teo.mydb.core.ddl.SchemaDraft
import dev.teo.mydb.core.ddl.SequenceDraft
import dev.teo.mydb.core.ddl.ViewDraft
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.plugin.sql.SqlFileType
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Create dialog for objects without a tree (schema, view, sequence): a form, the live DDL preview and a
 * problem line, like the table dialog. OK runs the script on the DDL session.
 */
abstract class ObjectDialog(
    protected val project: Project,
    private val config: DataSourceConfig,
    private val database: String,
    title: String,
) : DialogWrapper(project, true) {
    private val previewDocument = EditorFactory.getInstance().createDocument("")
    private val preview = EditorFactory.getInstance().createViewer(previewDocument, project, EditorKind.PREVIEW).also {
        (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
    }
    private val problemsLabel = JBLabel()

    init { this.title = title }

    abstract fun statements(): List<String>
    abstract fun problems(): List<String>
    abstract fun Panel.form()
    abstract val schema: String?
    abstract fun refreshTarget(): RefreshTarget

    /** Script shown in the preview (tests). */
    fun previewText(): String = statements().joinToString("\n\n")

    protected fun refresh() {
        val problems = problems()
        val text = runCatching { previewText() }.getOrElse { "-- ${it.message}" }
        ApplicationManager.getApplication().runWriteAction { previewDocument.setText(text) }
        problemsLabel.text = if (problems.isEmpty()) "No problems" else "${problems.size} problem(s): " + problems.joinToString("; ")
        problemsLabel.foreground = if (problems.isEmpty()) JBUI.CurrentTheme.Label.disabledForeground() else com.intellij.ui.JBColor.RED
    }

    protected fun <T : JTextComponent> T.bind(apply: (String) -> Unit): T = apply {
        document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) { apply(text); refresh() } })
    }
    protected fun textField(initial: String?, apply: (String) -> Unit) = JBTextField(initial.orEmpty()).bind(apply)
    protected fun numberField(initial: Long?, apply: (Long?) -> Unit) = JBTextField(initial?.toString().orEmpty()).bind { apply(it.trim().toLongOrNull()) }
    protected fun checkField(label: String, initial: Boolean, apply: (Boolean) -> Unit) = JBCheckBox(label, initial).apply { addActionListener { apply(isSelected); refresh() } }

    override fun createCenterPanel(): JComponent {
        val form = panel { form() }
        val bottom = JPanel(BorderLayout()).apply {
            add(preview.component, BorderLayout.CENTER)
            add(problemsLabel, BorderLayout.SOUTH)
        }
        refresh()
        return Splitter(true, 0.55f).apply {
            firstComponent = JBScrollPane(form)
            secondComponent = bottom
            preferredSize = Dimension(JBUI.scale(640), JBUI.scale(520))
        }
    }

    override fun doOKAction() {
        val problems = problems()
        if (problems.isNotEmpty() && !MessageDialogBuilder.yesNo("Problems Found", problems.joinToString("\n") + "\n\nExecute anyway?").ask(contentPane)) return
        val statements = statements()
        isOKActionEnabled = false
        setErrorText(null)
        object : Task.Backgroundable(project, title, true) {
            private var error: String? = null
            override fun run(indicator: ProgressIndicator) { error = ObjectDdl.execute(project, config, database, schema, statements, refreshTarget()) }
            override fun onSuccess() { if (error == null) close(OK_EXIT_CODE) else { setErrorText(error); isOKActionEnabled = true } }
            override fun onThrowable(t: Throwable) { setErrorText(t.message ?: t.toString()); isOKActionEnabled = true }
        }.queue()
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(preview)
        super.dispose()
    }
}

class SchemaDialog(project: Project, config: DataSourceConfig, private val database: String, val draft: SchemaDraft = SchemaDraft()) :
    ObjectDialog(project, config, database, "Create Schema") {
    init { init() }
    override val schema: String? get() = null
    override fun statements() = PgObjectDdl.schema(draft)
    override fun problems() = PgObjectDdl.problems(draft.name)
    override fun refreshTarget() = RefreshTarget.All
    override fun Panel.form() {
        row("Name:") { cell(textField(draft.name) { draft.name = it.trim() }).align(AlignX.FILL) }
        row("Comment:") { cell(textField(draft.comment) { draft.comment = it }).align(AlignX.FILL) }
        row("Owner:") { cell(textField(draft.owner) { draft.owner = it.trim() }).align(AlignX.FILL) }
    }
}

class ViewDialog(project: Project, config: DataSourceConfig, private val database: String, val draft: ViewDraft) :
    ObjectDialog(project, config, database, "Create View") {
    init { init() }
    override val schema: String? get() = draft.schema
    override fun statements() = PgObjectDdl.view(draft)
    override fun problems() = PgObjectDdl.viewProblems(draft)
    override fun refreshTarget() = RefreshTarget.Tables(database, draft.schema, setOf(draft.name))
    override fun Panel.form() {
        row("Name:") { cell(textField(draft.name) { draft.name = it.trim() }).align(AlignX.FILL) }
        row("Comment:") { cell(textField(draft.comment) { draft.comment = it }).align(AlignX.FILL) }
        row { cell(checkField("Materialized", draft.materialized) { draft.materialized = it }) }
        row { cell(checkField("Create or replace", draft.orReplace) { draft.orReplace = it }) }
        row("Query:") {}
        row {
            cell(JBScrollPane(JBTextArea(draft.query, 8, 60).bind { draft.query = it })).align(AlignX.FILL)
        }
    }
}

class SequenceDialog(project: Project, config: DataSourceConfig, private val database: String, val draft: SequenceDraft) :
    ObjectDialog(project, config, database, "Create Sequence") {
    init { init() }
    override val schema: String? get() = draft.schema
    override fun statements() = PgObjectDdl.sequence(draft)
    override fun problems() = PgObjectDdl.sequenceProblems(draft)
    override fun refreshTarget() = RefreshTarget.Schema(database, draft.schema)
    override fun Panel.form() {
        row("Name:") { cell(textField(draft.name) { draft.name = it.trim() }).align(AlignX.FILL) }
        row("Comment:") { cell(textField(draft.comment) { draft.comment = it }).align(AlignX.FILL) }
        row("Data type:") {
            cell(com.intellij.openapi.ui.ComboBox(arrayOf("", "smallint", "integer", "bigint")).apply {
                selectedItem = draft.type.orEmpty()
                addActionListener { draft.type = (selectedItem as String).ifBlank { null }; refresh() }
            })
        }
        row("Start with:") { cell(numberField(draft.start) { draft.start = it }) }
        row("Increment by:") { cell(numberField(draft.increment) { draft.increment = it }) }
        row("Min value:") { cell(numberField(draft.min) { draft.min = it }) }
        row("Max value:") { cell(numberField(draft.max) { draft.max = it }) }
        row("Cache:") { cell(numberField(draft.cache) { draft.cache = it }) }
        row { cell(checkField("Cycle", draft.cycle) { draft.cycle = it }) }
    }
}
