package dev.teo.mydb.plugin.sql

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import dev.teo.mydb.plugin.sql.psi.*
import dev.teo.mydb.plugin.sql.SqlElementTypes as E

class SqlParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = SqlLexer()
    override fun createParser(project: Project?) = SqlParser()
    override fun getFileNodeType(): IFileElementType = E.FILE
    override fun getCommentTokens(): TokenSet = SqlTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = SqlTokenTypes.STRINGS
    override fun getWhitespaceTokens(): TokenSet = SqlTokenTypes.WHITESPACES
    override fun createFile(viewProvider: FileViewProvider): PsiFile = SqlFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        E.TABLE_REF -> SqlTableRef(node)
        E.ALIAS_DEF -> SqlAliasDef(node)
        E.COLUMN_REF -> SqlColumnRef(node)
        E.TABLE_DEF -> SqlTableDef(node)
        E.COLUMN_DEF -> SqlColumnDef(node)
        E.FUNCTION_CALL -> SqlFunctionCall(node)
        E.SELECT_ITEM -> SqlSelectItem(node)
        E.CTE -> SqlCte(node)
        E.JOIN_CLAUSE -> SqlJoinClause(node)
        E.QUERY_EXPRESSION -> SqlQueryExpression(node)
        E.SUBQUERY -> SqlSubquery(node)
        in E.STATEMENTS -> SqlStatement(node)
        else -> SqlCompositeElement(node)
    }
}

class SqlFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, SqlLanguage) {
    override fun getFileType() = SqlFileType
    override fun toString() = "SQL file"
    val statements: List<SqlStatement> get() = children.filterIsInstance<SqlStatement>()
}
