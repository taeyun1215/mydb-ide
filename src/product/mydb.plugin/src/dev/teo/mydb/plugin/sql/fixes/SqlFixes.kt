package dev.teo.mydb.plugin.sql.fixes

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.core.datasource.SchemaRef
import dev.teo.mydb.plugin.MyDbBundle
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.psi.*

/** First fix on every unresolved reference: re-read the metadata. */
class RefreshSchemaFix : IntentionAction, PriorityAction, com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("fix.refresh.schema")
    override fun getFamilyName() = text
    override fun getPriority() = PriorityAction.Priority.TOP
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = file != null && SqlDataSourceBinding.context(file) != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val ctx = SqlDataSourceBinding.context(file ?: return) ?: return
        ModelService.getInstance(project).refresh(ctx.config)
    }
}

/** Adds the referenced schema to the introspection scope. */
class SelectSchemaFix(private val schema: String?) : IntentionAction, com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("fix.select.schema")
    override fun getFamilyName() = text
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val ctx = file?.let { SqlDataSourceBinding.context(it) } ?: return false
        return schema != null && ctx.db?.allSchemaNames?.contains(schema) == true && ctx.db?.schema(schema) == null
    }
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val ctx = SqlDataSourceBinding.context(file ?: return) ?: return
        val updated = ctx.config.copy(introspectionScope = ctx.config.introspectionScope.plus(SchemaRef(ctx.database, schema!!)))
        DataSourceStorage.getInstance(project).save(updated)
        ModelService.getInstance(project).refresh(updated)
    }
}

/** Inserts a `create table` skeleton for an unknown table before the statement. */
class CreateTableDefinitionFix(ref: SqlTableRef) : IntentionAction, com.intellij.openapi.project.DumbAware {
    private val pointer = com.intellij.psi.SmartPointerManager.createPointer(ref)
    override fun getText() = MyDbBundle.message("fix.create.table")
    override fun getFamilyName() = text
    override fun startInWriteAction() = true
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pointer.element != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val ref = pointer.element ?: return
        val stmt = PsiTreeUtil.getParentOfType(ref, SqlStatement::class.java) ?: return
        val doc = editor?.document ?: return
        doc.insertString(stmt.textRange.startOffset, "create table ${ref.text}\n(\n    id integer primary key\n);\n\n")
    }
}

/** `orders` → `shop.orders`. */
class QualifyIdentifierIntention : PsiElementBaseIntentionAction(), com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("intention.qualify")
    override fun getFamilyName() = text
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val ref = PsiTreeUtil.getParentOfType(element, SqlTableRef::class.java, false) ?: return false
        return ref.segments.size == 1 && (SqlResolve.resolveTable(ref) as? DbObjectElement) != null
    }
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val ref = PsiTreeUtil.getParentOfType(element, SqlTableRef::class.java, false) ?: return
        val obj = SqlResolve.resolveTable(ref) as? DbObjectElement ?: return
        replaceText(editor ?: return, ref, "${quote(obj.schema)}.${ref.text}")
    }
}

/** `shop.orders` → `orders` when the schema is the current one. */
class UnqualifyIdentifierIntention : PsiElementBaseIntentionAction(), com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("intention.unqualify")
    override fun getFamilyName() = text
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val ref = PsiTreeUtil.getParentOfType(element, SqlTableRef::class.java, false) ?: return false
        val ctx = SqlResolve.context(ref) ?: return false
        return ref.segments.size == 2 && ref.schemaName == ctx.schema
    }
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val ref = PsiTreeUtil.getParentOfType(element, SqlTableRef::class.java, false) ?: return
        replaceText(editor ?: return, ref, ref.text.substringAfter('.'))
    }
}

/** Adds double quotes around an identifier. */
class QuoteIdentifierIntention : PsiElementBaseIntentionAction(), com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("intention.quote")
    override fun getFamilyName() = text
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement) =
        element.node.elementType == dev.teo.mydb.plugin.sql.SqlTokenTypes.IDENT && (element.parent is SqlTableRef || element.parent is SqlColumnRef)
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        editor ?: return
        editor.document.replaceString(element.textRange.startOffset, element.textRange.endOffset, "\"${element.text.lowercase()}\"")
    }
}

/** `a join b on …` → `b join a on …`; changes meaning for outer joins. */
class FlipJoinIntention : PsiElementBaseIntentionAction(), com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("intention.flip.join")
    override fun getFamilyName() = text
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val join = PsiTreeUtil.getParentOfType(element, SqlJoinClause::class.java, false) ?: return false
        return leftItem(join) != null && rightItem(join) != null
    }
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val join = PsiTreeUtil.getParentOfType(element, SqlJoinClause::class.java, false) ?: return
        val left = leftItem(join) ?: return
        val right = rightItem(join) ?: return
        val doc = editor?.document ?: return
        val l = left.second; val r = right.second
        val lText = doc.getText(l); val rText = doc.getText(r)
        // replace the right one first so offsets of the left stay valid
        doc.replaceString(r.startOffset, r.endOffset, lText)
        doc.replaceString(l.startOffset, l.endOffset, rText)
    }

    /** The from item before the join keyword, with its alias. */
    private fun leftItem(join: SqlJoinClause): Pair<PsiElement, com.intellij.openapi.util.TextRange>? {
        var p = join.prevSibling
        while (p != null && (p is com.intellij.psi.PsiWhiteSpace || p is com.intellij.psi.PsiComment)) p = p.prevSibling
        val alias = p as? SqlAliasDef
        val item = if (alias != null) alias.target else p
        if (item !is SqlTableRef && item !is SqlSubquery) return null
        return item to com.intellij.openapi.util.TextRange(item.textRange.startOffset, (alias ?: item).textRange.endOffset)
    }

    private fun rightItem(join: SqlJoinClause): Pair<PsiElement, com.intellij.openapi.util.TextRange>? {
        val item = join.children.firstOrNull { it is SqlTableRef || it is SqlSubquery } ?: return null
        val alias = (item as? SqlTableRef)?.alias
        return item to com.intellij.openapi.util.TextRange(item.textRange.startOffset, (alias ?: item).textRange.endOffset)
    }
}

/** `from orders o` → `from (select * from orders) o`; the table name becomes the alias when there is none. */
class WrapInSubqueryIntention : PsiElementBaseIntentionAction(), com.intellij.openapi.project.DumbAware {
    override fun getText() = MyDbBundle.message("intention.to.subquery")
    override fun getFamilyName() = text
    /** A table reference directly in a FROM or JOIN clause (not the target of UPDATE/DELETE/INSERT). */
    private fun target(element: PsiElement): SqlTableRef? {
        val ref = PsiTreeUtil.getParentOfType(element, SqlTableRef::class.java, false) ?: return null
        val E = dev.teo.mydb.plugin.sql.SqlElementTypes
        val owner = generateSequence(ref.parent) { it.parent }.firstOrNull { p ->
            p is com.intellij.psi.PsiFile || p.node.elementType == E.FROM_CLAUSE || p.node.elementType == E.JOIN_CLAUSE || p.node.elementType in E.STATEMENTS
        } ?: return null
        return ref.takeIf { owner.node.elementType == E.FROM_CLAUSE || owner.node.elementType == E.JOIN_CLAUSE }
    }
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement) = target(element) != null
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val ref = target(element) ?: return
        val alias = if (ref.alias != null) "" else " " + (ref.tableName ?: return)
        replaceText(editor ?: return, ref, "(select * from ${ref.text})$alias")
    }
}

private fun quote(s: String) = dev.teo.mydb.core.ddl.PgDdlGenerator.quote(s)

private fun replaceText(editor: Editor, e: PsiElement, text: String) =
    editor.document.replaceString(e.textRange.startOffset, e.textRange.endOffset, text)
