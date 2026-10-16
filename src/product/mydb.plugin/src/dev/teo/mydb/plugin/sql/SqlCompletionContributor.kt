package dev.teo.mydb.plugin.sql

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.teo.mydb.core.model.DbSchema
import dev.teo.mydb.plugin.sql.psi.*
import dev.teo.mydb.plugin.sql.SqlElementTypes as E

/**
 * Database-aware completion:
 * table names in FROM/JOIN positions, FK-based join conditions after `ON`, columns and aliases in expressions.
 */
class SqlCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(SqlLanguage), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                SqlCompletion(parameters, result).run()
            }
        })
    }
}

private class SqlCompletion(private val parameters: CompletionParameters, private val result: CompletionResultSet) {
    private val position = parameters.position
    private val ctx = SqlResolve.context(parameters.originalFile)

    fun run() {
        val tableRef = PsiTreeUtil.getParentOfType(position, SqlTableRef::class.java)
        val columnRef = PsiTreeUtil.getParentOfType(position, SqlColumnRef::class.java)
        when {
            tableRef != null -> tableNames(tableRef)
            columnRef != null -> {
                if (isFirstInJoinCondition(columnRef)) joinConditions(columnRef)
                columns(columnRef)
                if (!columnRef.isQualifiedAtCaret() && !columnRef.isInColumnList && !columnRef.isSetTarget) keywords()
            }
            else -> keywords()
        }
    }

    // ---- tables -------------------------------------------------------------------------------

    private fun tableNames(ref: SqlTableRef) {
        val ctx = ctx ?: return
        val db = ctx.db ?: return
        val qualifier = ref.segmentsBeforeCaret()
        val schemas: List<DbSchema> = when (qualifier.size) {
            0 -> ctx.searchPath
            else -> listOfNotNull(db.schema(qualifier.last()))
        }
        for (s in schemas) {
            for (t in s.tables) {
                result.addElement(priority(LookupElementBuilder.create(t.name)
                    .withIcon(if (t.isView) AllIcons.Nodes.DataSchema else AllIcons.Nodes.DataTables)
                    .withTailText(" (${db.name}.${s.name})", true)
                    .withTypeText(ctx.config.name), if (t.isView) 90.0 else 100.0))
            }
            // sequences are offered in FROM positions too
            for (seq in s.sequences) {
                result.addElement(priority(LookupElementBuilder.create(seq.name).withIcon(AllIcons.Nodes.Static)
                    .withTailText(" (${db.name}.${s.name})", true).withTypeText(ctx.config.name), 50.0))
            }
        }
        if (qualifier.isEmpty()) {
            db.schemas.forEach { s -> result.addElement(priority(LookupElementBuilder.create(s.name).withIcon(AllIcons.Nodes.Package).withTypeText("schema"), 40.0)) }
            // CTEs of the enclosing statement
            PsiTreeUtil.getParentOfType(position, SqlStatement::class.java)?.let { st ->
                PsiTreeUtil.findChildrenOfType(st, SqlCte::class.java).mapNotNull { it.cteName }.forEach {
                    result.addElement(priority(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Variable).withTypeText("CTE"), 110.0))
                }
            }
        }
    }

    // ---- join conditions -------------------------------------------------------

    private fun isFirstInJoinCondition(ref: SqlColumnRef): Boolean {
        val cond = ref.parent
        if (cond?.node?.elementType != E.JOIN_CONDITION) return false
        // the reference is the first expression after ON
        return cond.children.firstOrNull { it is SqlCompositeElement } == ref && !ref.isQualifiedAtCaret()
    }

    private fun joinConditions(ref: SqlColumnRef) {
        val join = PsiTreeUtil.getParentOfType(ref, SqlJoinClause::class.java) ?: return
        val scope = SqlResolve.visibleSources(join).firstOrNull() ?: return
        val right = scope.filterIsInstance<FromSource.Table>().firstOrNull { PsiTreeUtil.isAncestor(join, it.ref, false) && it.table != null } ?: return
        val lefts = scope.filterIsInstance<FromSource.Table>().filter { it !== right && it.table != null && it.ref.textOffset < join.textOffset }
        var rank = 1000.0
        for (left in lefts) {
            // FK from the left table to the right one, then the other direction
            left.table!!.foreignKeys.filter { it.refTable == right.table!!.name }.forEach { fk ->
                val text = fk.columns.zip(fk.refColumns).joinToString(" and ") { (a, b) -> "${left.visibleName}.$a = ${right.visibleName}.$b" }
                result.addElement(priority(LookupElementBuilder.create(text).withIcon(AllIcons.Nodes.DataColumn).withTypeText("foreign key"), rank--))
            }
            right.table!!.foreignKeys.filter { it.refTable == left.table.name }.forEach { fk ->
                val text = fk.columns.zip(fk.refColumns).joinToString(" and ") { (a, b) -> "${right.visibleName}.$a = ${left.visibleName}.$b" }
                result.addElement(priority(LookupElementBuilder.create(text).withIcon(AllIcons.Nodes.DataColumn).withTypeText("foreign key"), rank--))
            }
        }
    }

    // ---- columns and aliases -------------------------------------------------------------------

    private fun columns(ref: SqlColumnRef) {
        if (ref.isInColumnList || ref.isSetTarget) {
            SqlResolve.dmlTarget(ref)?.table?.columns?.forEach { c -> result.addElement(column(c.name, c.type, null, 80.0)) }
            return
        }
        val qualifier = ref.segmentsBeforeCaret()
        val scopes = SqlResolve.visibleSources(ref)
        if (qualifier.isNotEmpty()) {
            val name = qualifier.last()
            scopes.flatten().firstOrNull { it.visibleName == name }?.let { src -> columnsOf(src, null, 80.0) }
            return
        }
        val inner = scopes.firstOrNull().orEmpty()
        // aliases first, then qualified columns of each source
        inner.forEach { src ->
            result.addElement(priority(LookupElementBuilder.create(src.visibleName).withIcon(AllIcons.Nodes.Variable).withTypeText((src as? FromSource.Table)?.table?.name ?: "subquery"), 90.0))
        }
        val qualify = inner.size > 1
        inner.forEach { src -> columnsOf(src, if (qualify) src.visibleName else null, 80.0) }
    }

    private fun columnsOf(src: FromSource, qualifier: String?, p: Double) {
        when (src) {
            is FromSource.Table -> src.table?.columns?.forEach { c -> result.addElement(column(c.name, c.type, qualifier, p)) }
            is FromSource.Derived -> src.columns.forEach { c -> result.addElement(column(c, "", qualifier, p)) }
        }
    }

    private fun column(name: String, type: String, qualifier: String?, p: Double): LookupElement {
        val text = if (qualifier != null) "$qualifier.$name" else name
        return priority(LookupElementBuilder.create(text).withLookupString(name).withIcon(AllIcons.Nodes.DataColumn).withTypeText(type), p)
    }

    private fun keywords() {
        KEYWORDS.forEach { k -> result.addElement(priority(LookupElementBuilder.create(k).bold(), 10.0)) }
    }

    private fun priority(e: LookupElement, p: Double) = PrioritizedLookupElement.withPriority(e, p)

    /** Dotted segments that precede the caret inside a reference (`shop.` → [shop]). */
    private fun PsiElement.segmentsBeforeCaret(): List<String> {
        val caret = parameters.offset
        return nameSegments(this).filter { textRange.startOffset + it.rangeInElement.endOffset <= caret && !it.name.contains(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED.lowercase()) }
            .map { it.name }
    }

    private fun SqlColumnRef.isQualifiedAtCaret() = segmentsBeforeCaret().isNotEmpty()

    companion object {
        val KEYWORDS = listOf("select", "from", "where", "join", "left join", "inner join", "on", "group by", "order by", "having",
            "limit", "offset", "and", "or", "not", "in", "exists", "between", "like", "is null", "is not null", "as", "distinct",
            "insert into", "values", "update", "set", "delete from", "returning", "union", "union all", "case", "when", "then", "else", "end")
    }
}
