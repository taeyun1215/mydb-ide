package dev.teo.mydb.plugin.sql.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.util.IncorrectOperationException

/** What a reference points at; used for messages. */
enum class SqlRefKind { TABLE, SCHEMA, ALIAS_OR_TABLE, COLUMN }

abstract class SqlReference(element: PsiElement, range: TextRange, val refKind: SqlRefKind, val refName: String) :
    PsiReferenceBase<PsiElement>(element, range, false) {

    override fun handleElementRename(newElementName: String): PsiElement {
        val token = element.node.findLeafElementAt(rangeInElement.startOffset) ?: throw IncorrectOperationException()
        val replacement = SqlElementFactory.identifier(element.project, newElementName)
        token.treeParent.replaceChild(token, replacement)
        return element
    }

    override fun isReferenceTo(target: PsiElement): Boolean {
        val r = resolve() ?: return false
        return r == target || r.isEquivalentTo(target)
    }
}

class TableReference(ref: SqlTableRef, seg: NameSegment) : SqlReference(ref, seg.rangeInElement, SqlRefKind.TABLE, seg.name) {
    override fun resolve(): PsiElement? = SqlResolve.resolveTable(element as SqlTableRef)
}

class SchemaReference(e: PsiElement, seg: NameSegment) : SqlReference(e, seg.rangeInElement, SqlRefKind.SCHEMA, seg.name) {
    override fun resolve(): PsiElement? {
        val ctx = SqlResolve.context(element) ?: return null
        return if (ctx.db?.schema(refName) != null) element else null
    }
}

/** First segment of `x.col`: an alias, table name or CTE visible in scope. */
class QualifierReference(ref: SqlColumnRef, seg: NameSegment) : SqlReference(ref, seg.rangeInElement, SqlRefKind.ALIAS_OR_TABLE, seg.name) {
    override fun resolve(): PsiElement? = SqlResolve.visibleSources(element).flatten().firstOrNull { it.visibleName == refName }?.element
}

class ColumnReference(ref: SqlColumnRef, seg: NameSegment, private val qualifier: String?) : SqlReference(ref, seg.rangeInElement, SqlRefKind.COLUMN, seg.name) {
    override fun resolve(): PsiElement? {
        val ref = element as SqlColumnRef
        if (ref.isInColumnList || ref.isSetTarget) return SqlResolve.dmlTarget(ref)?.let { SqlResolve.column(it, refName) }
        val scopes = SqlResolve.visibleSources(ref)
        for (scope in scopes) {
            val candidates = if (qualifier != null) scope.filter { it.visibleName == qualifier } else scope
            for (src in candidates) SqlResolve.column(src, refName)?.let { return it }
            if (qualifier != null && candidates.isNotEmpty()) return null
        }
        // a bare name may also be a select-list alias (ORDER BY total)
        if (qualifier == null) {
            val q = com.intellij.psi.util.PsiTreeUtil.getParentOfType(ref, SqlQueryExpression::class.java)
            q?.let { com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(it, SqlSelectItem::class.java).firstOrNull { item -> item.alias?.aliasName == refName } }
                ?.let { return it.alias }
        }
        return null
    }

    /** True when more than one visible source has a column with this name (ambiguous reference). */
    fun isAmbiguous(): Boolean {
        if (qualifier != null) return false
        val scope = SqlResolve.visibleSources(element).firstOrNull() ?: return false
        return scope.count { refName in it.columnNames() } > 1
    }
}

object SqlReferences {
    fun forTableRef(ref: SqlTableRef): Array<PsiReference> {
        val segs = ref.segments
        if (segs.isEmpty()) return PsiReference.EMPTY_ARRAY
        val result = mutableListOf<PsiReference>(TableReference(ref, segs.last()))
        if (segs.size >= 2) result += SchemaReference(ref, segs[segs.size - 2])
        return result.toTypedArray()
    }

    fun forColumnRef(ref: SqlColumnRef): Array<PsiReference> {
        val segs = ref.segments
        if (segs.isEmpty()) return PsiReference.EMPTY_ARRAY
        if (ref.text.endsWith("*")) {
            return if (segs.isNotEmpty()) arrayOf(QualifierReference(ref, segs.last())) else PsiReference.EMPTY_ARRAY
        }
        return when (segs.size) {
            1 -> arrayOf(ColumnReference(ref, segs[0], null))
            else -> arrayOf(QualifierReference(ref, segs[segs.size - 2]), ColumnReference(ref, segs.last(), segs[segs.size - 2].name))
        }
    }
}
