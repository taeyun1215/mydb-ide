package dev.teo.mydb.plugin.generator

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.Topic
import dev.teo.mydb.core.ddl.ConstraintPlacement
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.ddl.SchemaQualification
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.SqlFileType
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import javax.swing.JPanel

/** Explorer selection changes, so tool windows can follow the selected object. */
fun interface ExplorerSelectionListener {
    fun selectionChanged(nodes: List<ExplorerNode>)

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("MyDB explorer selection", ExplorerSelectionListener::class.java)
    }
}

/** DDL text for a schema or a table/view node with the generator options. */
object SqlGenerator {
    fun generate(project: Project, node: ExplorerNode, options: DdlOptions, mode: dev.teo.mydb.core.ddl.GenerationMode = dev.teo.mydb.core.ddl.GenerationMode.CREATE,
                 cascade: Boolean = false): String? {
        val (config, database, schemaName) = when (node) {
            is ExplorerNode.Schema -> Triple(node.config, node.database, node.schema.name)
            is ExplorerNode.Table -> Triple(node.config, node.database, node.schema)
            else -> return null
        }
        val schema = ModelService.getInstance(project).model(config.id)?.schema(database, schemaName) ?: return null
        if (!dev.teo.mydb.plugin.util.Dialects.hasPostgresDdl(config)) {
            // SQLite and MySQL: the DDL kept by the server; the generator options are PostgreSQL's
            val tables = if (node is ExplorerNode.Table) listOfNotNull(schema.table(node.table.name)) else schema.tables
            return tables.joinToString("\n\n") { dev.teo.mydb.plugin.util.Dialects.ddl(project, config, database, schemaName, it) }
        }
        val gen = PgDdlGenerator(options.copy(contextSchema = options.contextSchema ?: schemaName)) { s, t ->
            ModelService.getInstance(project).model(config.id)?.schema(database, s)?.table(t)
        }
        val tables = when (node) {
            is ExplorerNode.Schema -> schema.tables
            is ExplorerNode.Table -> listOfNotNull(schema.table(node.table.name))
            else -> return null
        }
        val create = when (node) {
            is ExplorerNode.Schema -> gen.schemaScript(schema)
            else -> tables.singleOrNull()?.let { if (it.isView) gen.view(schemaName, it) else gen.table(schemaName, it) } ?: return null
        }
        val drop = { gen.drops(schemaName, tables, ifExists = true, cascade = cascade) }
        return when (mode) {
            dev.teo.mydb.core.ddl.GenerationMode.CREATE -> create
            dev.teo.mydb.core.ddl.GenerationMode.DROP -> drop()
            dev.teo.mydb.core.ddl.GenerationMode.DROP_AND_CREATE -> drop() + "\n\n" + create
        }
    }
}

class SqlGeneratorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SqlGeneratorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object { const val ID = "MyDB SQL Generator" }
}

/** Options on the left, generated script on the right with copy / save / open in console. */
class SqlGeneratorPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private var node: ExplorerNode? = null
    private val qualify = ComboBox(SchemaQualification.entries.toTypedArray())
    private val placement = ComboBox(ConstraintPlacement.entries.toTypedArray())
    private val createOrReplace = JBCheckBox("CREATE OR REPLACE")
    private val ifNotExists = JBCheckBox("IF NOT EXISTS")
    private val skipOwner = JBCheckBox("Skip owner")
    private val mode = ComboBox(dev.teo.mydb.core.ddl.GenerationMode.entries.toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.title }
    }
    private val comments = JBCheckBox("Comments", true)
    private val indexes = JBCheckBox("Indexes", true)
    private val cascade = JBCheckBox("DROP … CASCADE")
    private val document = EditorFactory.getInstance().createDocument("")
    private val viewer = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW).also {
        (it as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, SqlFileType)
    }

    init {
        listOf(qualify, placement, mode).forEach { it.addActionListener { regenerate() } }
        listOf(createOrReplace, ifNotExists, skipOwner, comments, indexes, cascade).forEach { it.addActionListener { regenerate() } }
        val options = panel {
            row("Mode:") { cell(mode) }
            row("Schema names:") { cell(qualify) }
            row("Constraints:") { cell(placement) }
            row { cell(createOrReplace) }
            row { cell(ifNotExists) }
            row { cell(skipOwner) }
            row { cell(comments) }
            row { cell(indexes) }
            row { cell(cascade) }
        }
        val group = DefaultActionGroup(
            act("Copy", AllIcons.Actions.Copy) { CopyPasteManager.getInstance().setContents(StringSelection(document.text)) },
            act("Save to File…", AllIcons.Actions.MenuSaveall) { save() },
            act("Open in Query Console", AllIcons.Nodes.Console) { openInConsole() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("MyDbSqlGenerator", group, false).also { it.targetComponent = this }
        val right = JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST); add(viewer.component, BorderLayout.CENTER) }
        add(options, BorderLayout.WEST)
        add(right, BorderLayout.CENTER)
        project.messageBus.connect(this).subscribe(ExplorerSelectionListener.TOPIC, ExplorerSelectionListener { nodes ->
            nodes.singleOrNull()?.takeIf { it is ExplorerNode.Schema || it is ExplorerNode.Table }?.let { show(it) }
        })
    }

    fun options() = DdlOptions(
        qualify = qualify.selectedItem as SchemaQualification,
        constraintPlacement = placement.selectedItem as ConstraintPlacement,
        createOrReplace = createOrReplace.isSelected,
        ifNotExists = ifNotExists.isSelected,
        skipOwner = skipOwner.isSelected,
        comments = comments.isSelected,
        indexes = indexes.isSelected,
    )

    fun setMode(m: dev.teo.mydb.core.ddl.GenerationMode) { mode.selectedItem = m; regenerate() }

    fun show(n: ExplorerNode) { node = n; regenerate() }
    fun text(): String = document.text

    fun setIfNotExists(value: Boolean) { ifNotExists.isSelected = value; regenerate() }
    fun setPlacement(value: ConstraintPlacement) { placement.selectedItem = value; regenerate() }

    private fun regenerate() {
        val text = node?.let { SqlGenerator.generate(project, it, options(), mode.selectedItem as dev.teo.mydb.core.ddl.GenerationMode, cascade.isSelected) }
            ?: "-- select a schema or a table in the database explorer"
        ApplicationManager.getApplication().runWriteAction { document.setText(text) }
    }

    private fun save() {
        val n = node ?: return
        val dlg = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save SQL", "", "sql"), this)
        dlg.save(n.label + ".sql")?.let { Files.writeString(it.file.toPath(), document.text) }
    }

    private fun openInConsole() {
        val n = node ?: return
        val file = ConsoleService.getInstance(project).openConsole(n.config, newConsole = true)
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project) { doc.setText(document.text) }
    }

    private fun act(text: String, icon: javax.swing.Icon, run: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    override fun dispose() { EditorFactory.getInstance().releaseEditor(viewer) }
}

/** ⌥⌘G: open the SQL generator on the selected object. */
class ShowSqlGeneratorAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun node(e: AnActionEvent) = e.getData(ExplorerDataKeys.SELECTED)?.singleOrNull()?.takeIf { it is ExplorerNode.Schema || it is ExplorerNode.Table }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = node(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = node(e) ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(SqlGeneratorToolWindowFactory.ID) ?: return
        tw.show { (tw.contentManager.contents.firstOrNull()?.component as? SqlGeneratorPanel)?.show(n) }
    }
}
