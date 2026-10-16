package dev.teo.mydb.plugin.sql.hints

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.plugin.sql.psi.*

/**
 * Shows the relationship between joined tables after the joined table, e.g. `1..n<->1:` when the
 * left table references the right one.
 */
class JoinCardinalityHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is SqlJoinClause) return
            val (offset, text) = JoinCardinality.hint(element) ?: return
            sink.addPresentation(InlineInlayPosition(offset, true), hintFormat = HintFormat.default) { text(text) }
        }
    }
}

object JoinCardinality {
    /** (offset after the joined table/alias, hint text) or null when no foreign key links the tables. */
    fun hint(join: SqlJoinClause): Pair<Int, String>? {
        val scope = SqlResolve.visibleSources(join).firstOrNull() ?: return null
        val right = scope.filterIsInstance<FromSource.Table>().firstOrNull { PsiTreeUtil.isAncestor(join, it.ref, false) } ?: return null
        val rightTable = right.table ?: return null
        val lefts = scope.filterIsInstance<FromSource.Table>().filter { it !== right && it.ref.textOffset < join.textOffset && it.table != null }
        val anchor = (right.ref.alias ?: right.ref).textRange.endOffset
        for (left in lefts) {
            val l = left.table!!
            if (l.foreignKeys.any { it.refTable == rightTable.name }) return anchor to "1..n<->1:"
            if (rightTable.foreignKeys.any { it.refTable == l.name }) return anchor to "1<->1..n:"
        }
        return null
    }
}
