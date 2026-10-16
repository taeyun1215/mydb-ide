package dev.teo.mydb.plugin.explorer

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.ddl.DdlOptions
import dev.teo.mydb.core.ddl.PgDdlGenerator
import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.core.model.DbColumn
import dev.teo.mydb.core.model.DbTable
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.console.OutputLog
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.objects.ObjectDdl
import dev.teo.mydb.plugin.refactoring.DbRename
import dev.teo.mydb.plugin.refactoring.DbRenameDialog
import dev.teo.mydb.plugin.sql.psi.DbObjectElement
import java.awt.Dimension
import javax.swing.JEditorPane

private fun AnActionEvent.single() = getData(ExplorerDataKeys.SELECTED)?.singleOrNull()

/** The selected table or column as a PSI element (shared by rename, find usages and quick doc). */
fun ExplorerNode.asElement(project: Project): DbObjectElement? = when (this) {
    is ExplorerNode.Table -> DbObjectElement(project, config.id, database, schema, table)
    is ExplorerNode.Column -> DbObjectElement(project, config.id, database, schema, table, column)
    else -> null
}

/** Quick documentation of tables and columns. */
object DbDocs {
    /** Platform documentation layout: definition (path), content (comment, columns), then the DDL as code. */
    fun html(project: Project, e: DbObjectElement): String = buildString {
        val t = e.table
        append(com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_START)
        append("${esc(e.kind)} <b>${esc(e.database)}.${esc(e.schema)}.${esc(t.name)}${e.column?.let { ".${esc(it.name)}" } ?: ""}</b>")
        append(com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_END)
        append(com.intellij.lang.documentation.DocumentationMarkup.CONTENT_START)
        (e.column?.comment ?: t.comment)?.let { append("<p>${esc(it)}</p>") }
        if (e.column != null) {
            append("<p><code>${esc(e.column.type)}${if (e.column.notNull) " not null" else ""}${e.column.default?.let { " default ${esc(it)}" } ?: ""}</code></p>")
        }
        append(com.intellij.lang.documentation.DocumentationMarkup.CONTENT_END)
        if (e.column == null) {
            append(com.intellij.lang.documentation.DocumentationMarkup.SECTIONS_START)
            t.columns.forEach { c ->
                val key = if (t.primaryKey?.columns?.contains(c.name) == true) " PK" else if (t.foreignKeys.any { c.name in it.columns }) " FK" else ""
                append(com.intellij.lang.documentation.DocumentationMarkup.SECTION_HEADER_START).append(esc(c.name)).append(com.intellij.lang.documentation.DocumentationMarkup.SECTION_SEPARATOR).append("<code>${esc(c.type)}</code>$key").append(com.intellij.lang.documentation.DocumentationMarkup.SECTION_END)
            }
            append(com.intellij.lang.documentation.DocumentationMarkup.SECTIONS_END)
            val config = dev.teo.mydb.plugin.datasource.DataSourceStorage.getInstance(project).find(e.dataSourceId)
            val ddl = config?.let { dev.teo.mydb.plugin.util.Dialects.ddl(project, it, e.database, e.schema, t) } ?: ""
            append(com.intellij.lang.documentation.DocumentationMarkup.CONTENT_START).append("<pre><code>${esc(ddl)}</code></pre>").append(com.intellij.lang.documentation.DocumentationMarkup.CONTENT_END)
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Ctrl+Q / F1 on table and column references in SQL. */
class SqlDocumentationProvider : AbstractDocumentationProvider(), com.intellij.openapi.project.DumbAware {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? DbObjectElement)?.let { DbDocs.html(it.project, it) }
}

/** F1 in the explorer: documentation popup of the selected table or column. */
class QuickDocAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project?.let { p -> e.single()?.asElement(p) } != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = e.single()?.asElement(project) ?: return
        // the platform documentation popup, like F1 in the SQL editor
        com.intellij.codeInsight.documentation.DocumentationManager.getInstance(project).showJavaDocInfo(element, null)
    }
}

/** ⇧F6 in the explorer: the rename refactoring of for the selected table or column. */
class RenameObjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project?.let { p -> e.single()?.asElement(p) } != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DbRenameDialog(project, e.single()?.asElement(project) ?: return).show()
    }
}

/** ⌥F7 in the explorer: usages of the selected table or column in SQL files and consoles. */
class FindUsagesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project?.let { p -> e.single()?.asElement(p) } != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DbRename.showUsages(project, e.single()?.asElement(project) ?: return)
    }
}

/** ⇧⌘: the data source's default query console. */
class JumpToConsoleAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.single()?.let { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val n = e.single() ?: return
        ConsoleService.getInstance(e.project ?: return).openConsole(n.config)
    }
}

/** Statement templates for a table, opened in a new console. */
object SqlTemplates {
    enum class Kind(val title: String) { SELECT("SELECT Statement"), INSERT("INSERT Statement"), UPDATE("UPDATE Statement"), DELETE("DELETE Statement") }

    fun statement(kind: Kind, schema: String, t: DbTable, dialect: dev.teo.mydb.core.exec.SqlDialect = dev.teo.mydb.core.exec.SqlDialect.POSTGRES): String {
        fun quote(s: String) = dialect.quote(s)
        val name = "${quote(schema)}.${quote(t.name)}"
        val key = t.primaryKey?.columns.orEmpty()
        val where = (key.ifEmpty { t.columns.take(1).map { it.name } }).joinToString(" AND ") { c -> "${quote(c)} = ${placeholder(t.column(c))}" }
        val writable = t.columns.filter { !it.isSerial && it.identity == null }
        return when (kind) {
            Kind.SELECT -> "SELECT ${t.columns.joinToString(", ") { quote(it.name) }}\nFROM $name;"
            Kind.INSERT -> "INSERT INTO $name (${writable.joinToString(", ") { quote(it.name) }})\nVALUES (${writable.joinToString(", ") { placeholder(it) }});"
            Kind.UPDATE -> "UPDATE $name\nSET ${writable.filter { it.name !in key }.joinToString(",\n    ") { "${quote(it.name)} = ${placeholder(it)}" }}\nWHERE $where;"
            Kind.DELETE -> "DELETE\nFROM $name\nWHERE $where;"
        }
    }

    private fun placeholder(c: DbColumn?): String {
        val type = c?.type?.lowercase().orEmpty()
        return when {
            type.contains("int") || type.startsWith("numeric") || type.startsWith("decimal") || type == "real" || type.startsWith("double") -> "0"
            type == "boolean" -> "false"
            type.contains("char") || type == "text" -> "''"
            else -> "null"
        }
    }
}

open class SqlTemplateAction(private val kind: SqlTemplates.Kind) : DumbAwareAction(kind.title) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = (e.single() as? ExplorerNode.Table)?.let { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.single() as? ExplorerNode.Table ?: return
        val file = ConsoleService.getInstance(project).openConsole(n.config, newConsole = true)
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project) { doc.setText(SqlTemplates.statement(kind, n.schema, n.table, dev.teo.mydb.core.exec.SqlDialect.of(n.config.dbms))) }
    }
}
class SelectTemplateAction : SqlTemplateAction(SqlTemplates.Kind.SELECT)
class InsertTemplateAction : SqlTemplateAction(SqlTemplates.Kind.INSERT)
class UpdateTemplateAction : SqlTemplateAction(SqlTemplates.Kind.UPDATE)
class DeleteTemplateAction : SqlTemplateAction(SqlTemplates.Kind.DELETE)

/** Truncate and Delete (drop) of a table or view, after confirmation; runs on the DDL session. */
object ObjectStatements {
    fun truncate(schema: String, t: DbTable, dbms: dev.teo.mydb.core.datasource.Dbms = dev.teo.mydb.core.datasource.Dbms.POSTGRES) =
        // SQLite has no TRUNCATE; an unqualified DELETE uses its truncate optimization
        if (dbms == dev.teo.mydb.core.datasource.Dbms.SQLITE) "delete from ${dev.teo.mydb.core.exec.SqlDialect.of(dbms).qualified(schema, t.name)};"
        else "truncate table ${dev.teo.mydb.core.exec.SqlDialect.of(dbms).qualified(schema, t.name)};"
    fun drop(schema: String, t: DbTable, dbms: dev.teo.mydb.core.datasource.Dbms = dev.teo.mydb.core.datasource.Dbms.POSTGRES) =
        "drop ${if (t.kind == dev.teo.mydb.core.model.RelationKind.MATERIALIZED_VIEW) "materialized view" else if (t.isView) "view" else "table"} ${dev.teo.mydb.core.exec.SqlDialect.of(dbms).qualified(schema, t.name)};"

    /** Runs [sql] and introspects the schema again. Blocking: call off the EDT. Returns the error, if any. */
    fun run(project: Project, n: ExplorerNode.Table, sql: String): String? {
        val handle = ConsoleService.getInstance(project).session(ObjectDdl.sessionKey(n.config), n.config.name, n.config)
        OutputLog.getInstance(project).statement(handle.title, "${n.database}.${n.schema}", sql)
        val r = handle.session.execute(sql)
        val error = (r as? StatementResult.Error)?.let { "[${it.sqlState ?: ""}] ${it.message}" }
        error?.let { OutputLog.getInstance(project).error(handle.title, it) }
        ModelService.getInstance(project).refreshNow(n.config, RefreshTarget.Schema(n.database, n.schema))
        return error
    }
}

abstract class ConfirmedStatementAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    abstract fun statement(n: ExplorerNode.Table): String?
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = (e.single() as? ExplorerNode.Table)?.takeIf { dev.teo.mydb.plugin.util.Dialects.hasConnection(it.config) }?.let(::statement) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.single() as? ExplorerNode.Table ?: return
        val sql = statement(n) ?: return
        if (Messages.showOkCancelDialog(project, sql, templatePresentation.text, "Execute", "Cancel", Messages.getWarningIcon()) != Messages.OK) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val error = ObjectStatements.run(project, n, sql)
            if (error != null) ApplicationManager.getApplication().invokeLater { Messages.showErrorDialog(project, error, templatePresentation.text) }
        }
    }
}

class TruncateTableAction : ConfirmedStatementAction() {
    override fun statement(n: ExplorerNode.Table) = if (n.table.isView) null else ObjectStatements.truncate(n.schema, n.table, n.config.dbms)
}

class DropObjectAction : ConfirmedStatementAction() {
    override fun statement(n: ExplorerNode.Table) = ObjectStatements.drop(n.schema, n.table, n.config.dbms)
}
