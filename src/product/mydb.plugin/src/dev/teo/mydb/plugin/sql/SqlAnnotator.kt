package dev.teo.mydb.plugin.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import dev.teo.mydb.plugin.MyDbBundle
import dev.teo.mydb.plugin.sql.fixes.*
import dev.teo.mydb.plugin.sql.psi.*

/**
 * Semantic colouring and unresolved-reference errors. Only files bound to a
 * data source with a loaded model report unresolved names; otherwise there is nothing to resolve against.
 */
/**
 * DumbAware: it reads the database model, not indexes. Consoles live outside the project, are never
 * indexed and count as "dumb" for the platform, so a non-dumb-aware annotator would never run there.
 */
class SqlAnnotator : Annotator, com.intellij.openapi.project.DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is SqlTableRef -> annotateRefs(element, holder)
            is SqlColumnRef -> annotateRefs(element, holder)
            is SqlAliasDef -> colour(holder, element, SqlColors.ALIAS)
            is SqlFunctionCall -> element.firstChild?.let { colour(holder, it, SqlColors.FUNCTION) }
        }
    }

    private fun annotateRefs(element: PsiElement, holder: AnnotationHolder) {
        val ctx = SqlResolve.context(element)
        val canResolve = ctx?.model != null
        for (ref in element.references.filterIsInstance<SqlReference>()) {
            val range = ref.rangeInElement.shiftRight(element.textRange.startOffset)
            val target = ref.resolve()
            if (target != null) {
                val key = when (ref.refKind) {
                    SqlRefKind.TABLE -> SqlColors.TABLE
                    SqlRefKind.SCHEMA -> SqlColors.SCHEMA
                    SqlRefKind.ALIAS_OR_TABLE -> if (target is SqlAliasDef) SqlColors.ALIAS else SqlColors.TABLE
                    SqlRefKind.COLUMN -> SqlColors.COLUMN
                }
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
                if (ref is ColumnReference && ref.isAmbiguous()) {
                    holder.newAnnotation(HighlightSeverity.WARNING, MyDbBundle.message("sql.ambiguous.column", ref.refName)).range(range).create()
                }
                continue
            }
            if (!canResolve) continue
            // columns of tables we know nothing about (unresolved table, function source) are not reported twice
            if (ref is ColumnReference && !columnScopeKnown(element)) continue
            val message = when (ref.refKind) {
                SqlRefKind.TABLE -> MyDbBundle.message("sql.unresolved.table", ref.refName)
                SqlRefKind.SCHEMA -> MyDbBundle.message("sql.unresolved.schema", ref.refName)
                SqlRefKind.ALIAS_OR_TABLE -> MyDbBundle.message("sql.unresolved.symbol", ref.refName)
                SqlRefKind.COLUMN -> MyDbBundle.message("sql.unresolved.column", ref.refName)
            }
            val b = holder.newAnnotation(HighlightSeverity.ERROR, message).range(range)
                .highlightType(com.intellij.codeInspection.ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                .withFix(RefreshSchemaFix())
            if (ref.refKind == SqlRefKind.TABLE && element is SqlTableRef) {
                b.withFix(SelectSchemaFix(element.schemaName)).withFix(CreateTableDefinitionFix(element))
            }
            b.create()
        }
    }

    /** True when every visible source of the innermost scope is a resolved table or a derived source. */
    private fun columnScopeKnown(e: PsiElement): Boolean {
        val ref = e as? SqlColumnRef ?: return true
        if (ref.isInColumnList || ref.isSetTarget) return SqlResolve.dmlTarget(ref)?.table != null
        val scope = SqlResolve.visibleSources(e).firstOrNull() ?: return false
        if (ref.isQualified) {
            val q = ref.segments[ref.segments.size - 2].name
            val src = scope.firstOrNull { it.visibleName == q } ?: return false
            return src !is FromSource.Table || src.table != null
        }
        return scope.isNotEmpty() && scope.all { it !is FromSource.Table || it.table != null }
    }

    private fun colour(holder: AnnotationHolder, e: PsiElement, key: com.intellij.openapi.editor.colors.TextAttributesKey) =
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(e.textRange).textAttributes(key).create()
}
