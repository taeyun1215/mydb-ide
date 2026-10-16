package dev.teo.mydb.plugin.sql.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.plugin.MyDbBundle
import dev.teo.mydb.plugin.sql.psi.*
import dev.teo.mydb.plugin.sql.SqlElementTypes as E

/** `delete from t` / `update t set …` without WHERE affects every row. */
class SqlMissingWhereInspection : LocalInspectionTool(), com.intellij.openapi.project.DumbAware {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is SqlStatement) return
            if (element.kind != E.DELETE_STATEMENT && element.kind != E.UPDATE_STATEMENT) return
            if (element.node.findChildByType(E.WHERE_CLAUSE) != null) return
            val anchor = element.firstChild ?: return
            holder.registerProblem(anchor, MyDbBundle.message("inspection.missing.where", anchor.text.uppercase()))
        }
    }
}

/**
 * Selected columns that are neither aggregated nor listed in GROUP BY.
 * A column is accepted when GROUP BY lists it (same text or same resolved column) or the primary key
 * of its table is grouped (PostgreSQL functional dependency).
 */
class SqlNonAggregatedColumnInspection : LocalInspectionTool(), com.intellij.openapi.project.DumbAware {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is SqlQueryExpression) return
            val groupBy = element.node.findChildByType(E.GROUP_BY_CLAUSE)?.psi ?: return
            val select = element.node.findChildByType(E.SELECT_CLAUSE)?.psi ?: return
            val grouped = PsiTreeUtil.findChildrenOfType(groupBy, SqlColumnRef::class.java)
            val groupedTargets = grouped.mapNotNull { it.references.lastOrNull()?.resolve() }.toSet()
            val groupedTexts = grouped.map { it.text.lowercase() }.toSet()
            val groupedKeys = groupedTargets.filterIsInstance<DbObjectElement>()
                .filter { o -> o.table.primaryKey?.columns == listOf(o.column?.name) }
                .map { it.table.name to it.schema }.toSet()
            for (item in PsiTreeUtil.getChildrenOfTypeAsList(select, SqlSelectItem::class.java)) {
                for (ref in PsiTreeUtil.findChildrenOfType(item, SqlColumnRef::class.java)) {
                    if (insideAggregate(ref, item) || PsiTreeUtil.getParentOfType(ref, SqlSubquery::class.java, true, SqlSelectItem::class.java) != null) continue
                    if (ref.text.lowercase() in groupedTexts) continue
                    val target = ref.references.lastOrNull()?.resolve()
                    if (target == null) continue // unresolved: already an error, not reported twice
                    if (target in groupedTargets) continue
                    if (target is DbObjectElement && (target.table.name to target.schema) in groupedKeys) continue
                    holder.registerProblem(ref, MyDbBundle.message("inspection.non.aggregated", ref.text))
                }
            }
        }
    }

    private fun insideAggregate(ref: PsiElement, stop: PsiElement): Boolean {
        var p = ref.parent
        while (p != null && p != stop) {
            if (p is SqlFunctionCall && p.functionName in AGGREGATES) return true
            p = p.parent
        }
        return false
    }

    companion object {
        val AGGREGATES = setOf("count", "sum", "avg", "min", "max", "array_agg", "string_agg", "bool_and", "bool_or", "every",
            "json_agg", "jsonb_agg", "json_object_agg", "jsonb_object_agg", "stddev", "variance", "percentile_cont", "percentile_disc", "mode")
    }
}
