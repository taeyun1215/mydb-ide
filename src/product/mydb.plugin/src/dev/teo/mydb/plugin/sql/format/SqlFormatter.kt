package dev.teo.mydb.plugin.sql.format

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.TokenSet
import dev.teo.mydb.plugin.sql.SqlElementTypes as E
import dev.teo.mydb.plugin.sql.SqlTokenTypes as T

/**
 * Default SQL layout: each clause of a query starts a line at the query's column,
 * JOINs are indented under the first table of FROM, tokens inside a clause are single-spaced.
 */
class SqlFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val root = SqlBlock(formattingContext.node, null, Indent.getNoneIndent())
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.containingFile, root, formattingContext.codeStyleSettings)
    }
}

private val CLAUSE_STARTS = TokenSet.create(
    E.SELECT_CLAUSE, E.FROM_CLAUSE, E.WHERE_CLAUSE, E.GROUP_BY_CLAUSE, E.HAVING_CLAUSE, E.ORDER_BY_CLAUSE,
    E.LIMIT_CLAUSE, E.SET_CLAUSE, E.VALUES_CLAUSE, E.RETURNING_CLAUSE,
)
private val QUERY_CONTAINERS = TokenSet.create(E.QUERY_EXPRESSION, E.UPDATE_STATEMENT, E.DELETE_STATEMENT, E.INSERT_STATEMENT)
private val NO_SPACE_BEFORE = TokenSet.create(T.COMMA, T.DOT, T.RPAREN, T.SEMICOLON, T.CAST, T.RBRACKET, T.LBRACKET)
private val NO_SPACE_AFTER = TokenSet.create(T.DOT, T.LPAREN, T.CAST, T.LBRACKET)

private val TABLE_ELEMENTS = TokenSet.create(E.COLUMN_DEF, E.CONSTRAINT_DEF)

/** Width of `from ` — JOINs line up after it plus a continuation indent. */
private const val JOIN_INDENT = 9

class SqlBlock(node: ASTNode, private val parentBlock: SqlBlock?, private val indent: Indent, alignment: Alignment? = null) :
    AbstractBlock(node, null, alignment) {

    /** In `create table`, column types line up in one column. */
    private val typeAlignment: Alignment? =
        if (node.elementType == E.CREATE_TABLE_STATEMENT) Alignment.createAlignment(true) else null

    override fun buildChildren(): List<Block> {
        val result = mutableListOf<Block>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (child.elementType != TokenType.WHITE_SPACE && child.textLength > 0) {
                val align = if (myNode.elementType == E.COLUMN_DEF && child.elementType == E.TYPE_ELEMENT) parentBlock?.typeAlignment else null
                result += SqlBlock(child, this, childIndent(child), align)
            }
            child = child.treeNext
        }
        return result
    }

    private fun childIndent(child: ASTNode): Indent = when {
        child.elementType == E.JOIN_CLAUSE && myNode.elementType == E.FROM_CLAUSE -> Indent.getSpaceIndent(JOIN_INDENT, true)
        myNode.elementType == E.CREATE_TABLE_STATEMENT && child.elementType in TABLE_ELEMENTS -> Indent.getSpaceIndent(4, false)
        myNode.elementType in QUERY_CONTAINERS -> Indent.getSpaceIndent(0, true)
        myNode.elementType == E.SUBQUERY && child.elementType == E.QUERY_EXPRESSION -> Indent.getNoneIndent()
        else -> Indent.getNoneIndent()
    }

    override fun getIndent(): Indent = indent

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val n1 = (child1 as? SqlBlock)?.node ?: return null
        val n2 = (child2 as SqlBlock).node
        val t1 = n1.elementType
        val t2 = n2.elementType
        val parent = myNode.elementType
        if (parent == E.CREATE_TABLE_STATEMENT && hasElementList()) when {
            // create table t\n(\n a integer,\n b text\n);
            t2 == T.LPAREN || t1 == T.LPAREN || t1 == T.COMMA || t2 == T.RPAREN -> return Spacing.createSpacing(0, 0, 1, false, 0)
            t2 == T.COMMA || t2 == T.SEMICOLON -> return Spacing.createSpacing(0, 0, 0, false, 0)
        }
        return when {
            parent == E.FILE -> Spacing.createSpacing(0, 0, if (t2 == T.SEMICOLON) 0 else 1, true, 2)
            t2 in NO_SPACE_BEFORE -> Spacing.createSpacing(0, 0, 0, false, 0)
            t1 in NO_SPACE_AFTER -> Spacing.createSpacing(0, 0, 0, false, 0)
            t2 == T.LPAREN && (parent == E.FUNCTION_CALL || t1 in T.IDENTIFIERS) -> Spacing.createSpacing(0, 0, 0, false, 0)
            parent in QUERY_CONTAINERS && (t2 in CLAUSE_STARTS || t2 == E.QUERY_EXPRESSION) -> Spacing.createSpacing(0, 0, 1, true, 1)
            parent in QUERY_CONTAINERS && t1 == E.WITH_CLAUSE -> Spacing.createSpacing(0, 0, 1, true, 1)
            parent == E.FROM_CLAUSE && t2 == E.JOIN_CLAUSE -> Spacing.createSpacing(0, 0, 1, true, 1)
            else -> Spacing.createSpacing(1, 1, 0, true, 1)
        }
    }

    private fun hasElementList() = myNode.getChildren(TABLE_ELEMENTS).isNotEmpty()

    override fun getChildAttributes(newChildIndex: Int) = ChildAttributes(Indent.getNoneIndent(), null)
    override fun isLeaf() = myNode.firstChildNode == null
}
