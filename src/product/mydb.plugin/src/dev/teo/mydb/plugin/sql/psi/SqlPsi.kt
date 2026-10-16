package dev.teo.mydb.plugin.sql.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import dev.teo.mydb.plugin.sql.SqlElementTypes as E
import dev.teo.mydb.plugin.sql.SqlTokenTypes as T

open class SqlCompositeElement(node: ASTNode) : ASTWrapperPsiElement(node)

class SqlStatement(node: ASTNode) : SqlCompositeElement(node) {
    val kind get() = node.elementType
}

class SqlQueryExpression(node: ASTNode) : SqlCompositeElement(node)
class SqlSubquery(node: ASTNode) : SqlCompositeElement(node) {
    val query: SqlQueryExpression? get() = PsiTreeUtil.getChildOfType(this, SqlQueryExpression::class.java)
}
class SqlJoinClause(node: ASTNode) : SqlCompositeElement(node)
class SqlFunctionCall(node: ASTNode) : SqlCompositeElement(node) {
    val functionName: String get() = node.firstChildNode.text.lowercase()
}
class SqlTableDef(node: ASTNode) : SqlCompositeElement(node) {
    val segments: List<NameSegment> get() = nameSegments(this)
}
class SqlColumnDef(node: ASTNode) : SqlCompositeElement(node) {
    val columnName: String? get() = node.firstChildNode?.let { normalizeIdentifier(it.text) }
}

class SqlCte(node: ASTNode) : SqlCompositeElement(node) {
    val alias: SqlAliasDef? get() = PsiTreeUtil.getChildOfType(this, SqlAliasDef::class.java)
    val query: SqlQueryExpression? get() = PsiTreeUtil.getChildOfType(this, SqlSubquery::class.java)?.query
    val cteName: String? get() = alias?.aliasName
}

class SqlSelectItem(node: ASTNode) : SqlCompositeElement(node) {
    val alias: SqlAliasDef? get() = PsiTreeUtil.getChildOfType(this, SqlAliasDef::class.java)
    /** Name of the output column: alias, else the last segment of a plain column reference. */
    val outputName: String? get() = alias?.aliasName ?: (firstChild as? SqlColumnRef)?.segments?.lastOrNull()?.name
    val isStar: Boolean get() = node.firstChildNode?.elementType == T.ASTERISK || (firstChild as? SqlColumnRef)?.text?.endsWith("*") == true
}

/** One identifier of a dotted name with its range inside the owning element. */
data class NameSegment(val name: String, val quoted: Boolean, val rangeInElement: TextRange)

fun normalizeIdentifier(text: String): String =
    when {
        text.startsWith("\"") -> text.removeSurrounding("\"").replace("\"\"", "\"")
        text.startsWith("`") -> text.removeSurrounding("`").replace("``", "`")
        else -> text.lowercase()
    }

fun nameSegments(element: PsiElement): List<NameSegment> {
    val result = mutableListOf<NameSegment>()
    var child = element.node.firstChildNode
    while (child != null) {
        val t = child.elementType
        if (t in T.IDENTIFIERS || t in T.ALL_KEYWORDS) {
            result += NameSegment(normalizeIdentifier(child.text), t == T.QUOTED_IDENT, TextRange.from(child.startOffsetInParent, child.textLength))
        }
        child = child.treeNext
    }
    return result
}

/** `[database.][schema.]table` in FROM/JOIN/INTO/UPDATE/REFERENCES positions. */
class SqlTableRef(node: ASTNode) : SqlCompositeElement(node) {
    val segments: List<NameSegment> get() = nameSegments(this)
    val tableName: String? get() = segments.lastOrNull()?.name
    val schemaName: String? get() = segments.dropLast(1).lastOrNull()?.name
    /** Alias declared right after this reference, if any. */
    val alias: SqlAliasDef? get() = generateSequence(nextSibling) { it.nextSibling }
        .firstOrNull { it !is com.intellij.psi.PsiWhiteSpace && it !is com.intellij.psi.PsiComment && it.node.elementType != T.kw("as") }
        as? SqlAliasDef

    override fun getReferences(): Array<PsiReference> = SqlReferences.forTableRef(this)
}

class SqlAliasDef(node: ASTNode) : SqlCompositeElement(node), com.intellij.psi.PsiNameIdentifierOwner {
    val aliasName: String get() = normalizeIdentifier(text)
    override fun getName(): String = aliasName
    override fun setName(name: String): PsiElement {
        val replacement = SqlElementFactory.identifier(project, name)
        node.replaceChild(node.firstChildNode, replacement)
        return this
    }
    override fun getNameIdentifier(): PsiElement = firstChild
    override fun getTextOffset(): Int = textRange.startOffset
    /** An alias is only visible inside its statement: local scope makes rename in place. */
    override fun getUseScope(): com.intellij.psi.search.SearchScope =
        com.intellij.psi.search.LocalSearchScope(com.intellij.psi.util.PsiTreeUtil.getParentOfType(this, SqlStatement::class.java) ?: containingFile)

    /** The element this alias names: a table reference, subquery or set-returning function. */
    val target: PsiElement? get() = generateSequence(prevSibling) { it.prevSibling }
        .firstOrNull { it is SqlTableRef || it is SqlSubquery || it is SqlFunctionCall || it is SqlSelectItem }
}

/** Column reference in an expression: `col`, `alias.col`, `schema.table.col`, `alias.*`. */
class SqlColumnRef(node: ASTNode) : SqlCompositeElement(node) {
    val segments: List<NameSegment> get() = nameSegments(this)
    val isQualified: Boolean get() = segments.size > 1
    override fun getReferences(): Array<PsiReference> = SqlReferences.forColumnRef(this)
}

fun PsiElement.isInside(type: com.intellij.psi.tree.IElementType): Boolean {
    var p: PsiElement? = parent
    while (p != null && p !is com.intellij.psi.PsiFile) { if (p.node.elementType == type) return true; p = p.parent }
    return false
}

val SqlColumnRef.isInColumnList: Boolean get() = parent?.node?.elementType == E.COLUMN_LIST
val SqlColumnRef.isSetTarget: Boolean get() = parent?.node?.elementType == E.SET_CLAUSE && run {
    // the column before '=' in SET a = …
    var n = node.treeNext
    while (n != null && n.elementType == com.intellij.psi.TokenType.WHITE_SPACE) n = n.treeNext
    n?.elementType == T.EQ
}
