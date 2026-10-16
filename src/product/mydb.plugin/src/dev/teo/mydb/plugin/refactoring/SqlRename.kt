package dev.teo.mydb.plugin.refactoring

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameHandler
import dev.teo.mydb.core.ddl.PgAlterGenerator
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.plugin.console.ConsoleService
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlFileType
import dev.teo.mydb.plugin.sql.SqlLanguage
import dev.teo.mydb.plugin.sql.psi.*

/** Aliases are renamed in place; database objects go through [DbObjectRenameHandler]. */
class SqlRefactoringSupportProvider : RefactoringSupportProvider() {
    // aliases are local names: plain in-place rename. The "member" path asks about usages in non-project files,
    // which every console is.
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = false
    override fun isInplaceRenameAvailable(element: PsiElement, context: PsiElement?) = element is SqlAliasDef
}

class SqlNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?) = name.lowercase() in dev.teo.mydb.plugin.sql.SqlTokenTypes.KEYWORDS.keys
    override fun isIdentifier(name: String, project: Project?) = name.isNotEmpty()
}

/** Where usages are searched. */
enum class RenameScope(val title: String) {
    PROJECT_AND_CONSOLES("Project files and consoles"), CONSOLES("Consoles of the data source"), OPEN_FILES("Open files")
}

/** Rename dialog options: extra text matches and the search scope. */
data class RenameOptions(
    val commentsAndStrings: Boolean = false,
    val textOccurrences: Boolean = false,
    val scope: RenameScope = RenameScope.PROJECT_AND_CONSOLES,
)

/** A plain-text match of the old name: in a SQL comment or string, or in a non-SQL file. */
data class TextOccurrence(val file: VirtualFile, val range: com.intellij.openapi.util.TextRange)

/** A usage of a database object inside some SQL file. */
data class DbUsage(val file: VirtualFile, val element: SqlReference) {
    val range get() = element.rangeInElement.shiftRight(element.element.textRange.startOffset)
}

/**
 * Rename of a table or column: DDL preview, usages in SQL files and consoles of the
 * data source, optional execution of the DDL, then text replacement of every usage.
 */
object DbRename {
    fun target(editor: Editor?, file: PsiFile?): DbObjectElement? {
        if (editor == null || file == null || file.language != SqlLanguage) return null
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        val owner = PsiTreeUtil.getParentOfType(leaf, SqlTableRef::class.java, SqlColumnRef::class.java) ?: return null
        val ref = owner.references.filterIsInstance<SqlReference>().firstOrNull {
            it.rangeInElement.shiftRight(owner.textRange.startOffset).containsOffset(offset)
        } ?: return null
        if (ref.refKind != SqlRefKind.TABLE && ref.refKind != SqlRefKind.COLUMN) return null
        return ref.resolve() as? DbObjectElement
    }

    fun dialect(target: DbObjectElement): dev.teo.mydb.core.exec.SqlDialect =
        DataSourceStorage.getInstance(target.project).find(target.dataSourceId)?.let { dev.teo.mydb.core.exec.SqlDialect.of(it.dbms) }
            ?: dev.teo.mydb.core.exec.SqlDialect.POSTGRES

    fun ddl(target: DbObjectElement, newName: String): String {
        val d = dialect(target)
        if (d == dev.teo.mydb.core.exec.SqlDialect.POSTGRES) {
            val g = PgAlterGenerator(target.schema)
            return if (target.column != null) g.renameColumn(target.table.name, target.column.name, newName) else g.renameTable(target.table.name, newName)
        }
        // SQLite and MySQL accept the same two forms with their own quoting
        val t = d.qualified(target.schema, target.table.name)
        return if (target.column != null) "alter table $t\n    rename column ${d.quote(target.column.name)} to ${d.quote(newName)};"
               else "alter table $t\n    rename to ${d.quote(newName)};"
    }

    /** SQL files of the project plus the consoles of the target's data source. */
    fun candidateFiles(project: Project, target: DbObjectElement, scope: RenameScope = RenameScope.PROJECT_AND_CONSOLES): List<VirtualFile> {
        val open = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.filter { it.fileType == SqlFileType }
        if (scope == RenameScope.OPEN_FILES) return open
        val consoleDir = ConsoleService.getInstance(project).consoleDir(target.dataSourceId)
        val consoles = VfsUtil.findFile(consoleDir, true)?.children?.filter { it.extension == "sql" }.orEmpty()
        if (scope == RenameScope.CONSOLES) return (consoles + open.filter { f -> consoles.any { it == f } }).distinct()
        val inProject = FileTypeIndex.getFiles(SqlFileType, GlobalSearchScope.projectScope(project)).toList()
        return (inProject + consoles + open).distinct()
    }

    private fun word(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** Old name in comments and strings of the searched SQL files, and in other project text files. */
    fun findTextOccurrences(project: Project, target: DbObjectElement, options: RenameOptions): List<TextOccurrence> {
        val out = mutableListOf<TextOccurrence>()
        val re = word(target.name)
        if (options.commentsAndStrings) {
            val psi = PsiManager.getInstance(project)
            for (vf in candidateFiles(project, target, options.scope)) {
                val f = psi.findFile(vf) ?: continue
                PsiTreeUtil.collectElements(f) { e ->
                    e.firstChild == null && (e.node.elementType in dev.teo.mydb.plugin.sql.SqlTokenTypes.COMMENTS || e.node.elementType in dev.teo.mydb.plugin.sql.SqlTokenTypes.STRINGS)
                }.forEach { leaf ->
                    re.findAll(leaf.text).forEach { m -> out += TextOccurrence(vf, com.intellij.openapi.util.TextRange(leaf.textRange.startOffset + m.range.first, leaf.textRange.startOffset + m.range.last + 1)) }
                }
            }
        }
        if (options.textOccurrences && options.scope == RenameScope.PROJECT_AND_CONSOLES) {
            val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
            index.iterateContent { vf ->
                if (!vf.isDirectory && vf.fileType != SqlFileType && !vf.fileType.isBinary && vf.length < 1_000_000) {
                    val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.text
                    text?.let { re.findAll(it).forEach { m -> out += TextOccurrence(vf, com.intellij.openapi.util.TextRange(m.range.first, m.range.last + 1)) } }
                }
                true
            }
        }
        return out
    }

    fun findUsages(project: Project, target: DbObjectElement, scope: RenameScope = RenameScope.PROJECT_AND_CONSOLES): List<DbUsage> {
        val psi = PsiManager.getInstance(project)
        return candidateFiles(project, target, scope).flatMap { vf ->
            val f = psi.findFile(vf) ?: return@flatMap emptyList()
            PsiTreeUtil.findChildrenOfAnyType(f, SqlTableRef::class.java, SqlColumnRef::class.java)
                .flatMap { it.references.filterIsInstance<SqlReference>() }
                .filter { r -> (r.refKind == SqlRefKind.TABLE || r.refKind == SqlRefKind.COLUMN) && r.resolve() == target }
                .map { DbUsage(vf, it) }
        }
    }

    /** Usages in the Find tool window. */
    fun showUsages(project: Project, target: DbObjectElement, tabText: String = "Usages of ${target.name}") {
        val usages = findUsages(project, target).map {
            com.intellij.usages.UsageInfo2UsageAdapter(com.intellij.usageView.UsageInfo(it.element.element, it.element.rangeInElement, false))
        }
        val p = com.intellij.usages.UsageViewPresentation().apply { this.tabText = tabText; codeUsagesString = "Usages of ${target.name}" }
        com.intellij.usages.UsageViewManager.getInstance(project).showUsages(com.intellij.usages.UsageTarget.EMPTY_ARRAY, usages.toTypedArray<com.intellij.usages.Usage>(), p)
    }

    /**
     * Applies the rename: DDL on the data source's session (when [executeDdl]), then replaces every
     * usage, then refreshes the table in the model. DDL failure leaves the code untouched.
     */
    fun perform(project: Project, target: DbObjectElement, newName: String, executeDdl: Boolean, options: RenameOptions = RenameOptions()) {
        if (executeDdl) {
            val config = DataSourceStorage.getInstance(project).find(target.dataSourceId) ?: error("data source not found")
            val handle = ConsoleService.getInstance(project).session("rename:${target.dataSourceId}", "rename", config)
            val r = handle.session.execute(ddl(target, newName))
            if (r is dev.teo.mydb.core.exec.StatementResult.Error) throw IllegalStateException(r.message)
        }
        val usages = findUsages(project, target, options.scope)
        val quoted = dialect(target).quote(newName)
        // code usages get a quoted identifier when needed; text matches get the plain new name
        val edits = usages.map { Triple(it.file, it.range, quoted) } +
            findTextOccurrences(project, target, options).map { Triple(it.file, it.range, newName) }
        WriteCommandAction.runWriteCommandAction(project, "Rename ${target.name}", null, {
            val docs = PsiDocumentManager.getInstance(project)
            edits.groupBy { it.first }.forEach { (vf, es) ->
                val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf) ?: return@forEach
                es.distinctBy { it.second }.sortedByDescending { it.second.startOffset }.forEach { (_, r, text) ->
                    doc.replaceString(r.startOffset, r.endOffset, text)
                }
                docs.commitDocument(doc)
            }
        })
        if (executeDdl) {
            val config = DataSourceStorage.getInstance(project).find(target.dataSourceId) ?: return
            val tables = setOf(target.table.name) + if (target.column == null) setOf(newName) else emptySet()
            ModelService.getInstance(project).refresh(config, RefreshTarget.Tables(target.database, target.schema, tables))
        }
    }
}

class DbObjectRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        DbRename.target(CommonDataKeys.EDITOR.getData(dataContext), CommonDataKeys.PSI_FILE.getData(dataContext)) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        val target = DbRename.target(editor, file) ?: return
        DbRenameDialog(project, target).show()
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val target = elements.singleOrNull() as? DbObjectElement ?: return
        DbRenameDialog(project, target).show()
    }
}
