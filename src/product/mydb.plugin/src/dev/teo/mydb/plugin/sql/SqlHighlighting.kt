package dev.teo.mydb.plugin.sql

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import dev.teo.mydb.plugin.sql.SqlTokenTypes as T

object SqlColors {
    val KEYWORD = createTextAttributesKey("MYDB_SQL_KEYWORD", D.KEYWORD)
    val IDENTIFIER = createTextAttributesKey("MYDB_SQL_IDENTIFIER", D.IDENTIFIER)
    val QUOTED_IDENTIFIER = createTextAttributesKey("MYDB_SQL_QUOTED_IDENTIFIER", D.IDENTIFIER)
    val STRING = createTextAttributesKey("MYDB_SQL_STRING", D.STRING)
    val NUMBER = createTextAttributesKey("MYDB_SQL_NUMBER", D.NUMBER)
    val COMMENT = createTextAttributesKey("MYDB_SQL_COMMENT", D.LINE_COMMENT)
    val BLOCK_COMMENT = createTextAttributesKey("MYDB_SQL_BLOCK_COMMENT", D.BLOCK_COMMENT)
    val OPERATOR = createTextAttributesKey("MYDB_SQL_OPERATOR", D.OPERATION_SIGN)
    val PARENS = createTextAttributesKey("MYDB_SQL_PARENS", D.PARENTHESES)
    val COMMA = createTextAttributesKey("MYDB_SQL_COMMA", D.COMMA)
    val DOT = createTextAttributesKey("MYDB_SQL_DOT", D.DOT)
    val PARAMETER = createTextAttributesKey("MYDB_SQL_PARAMETER", D.PARAMETER)
    val BAD = createTextAttributesKey("MYDB_SQL_BAD_CHARACTER", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER)

    // semantic colours applied by the annotator
    val TABLE = createTextAttributesKey("MYDB_SQL_TABLE", D.INSTANCE_FIELD)
    val SCHEMA = createTextAttributesKey("MYDB_SQL_SCHEMA", D.CLASS_NAME)
    val COLUMN = createTextAttributesKey("MYDB_SQL_COLUMN", D.IDENTIFIER)
    val ALIAS = createTextAttributesKey("MYDB_SQL_ALIAS", D.LOCAL_VARIABLE)
    val FUNCTION = createTextAttributesKey("MYDB_SQL_FUNCTION", D.FUNCTION_CALL)
}

class SqlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = SqlLexer()

    override fun getTokenHighlights(type: IElementType?): Array<TextAttributesKey> = pack(
        when {
            type == null -> null
            type in T.ALL_KEYWORDS -> SqlColors.KEYWORD
            type == T.IDENT -> SqlColors.IDENTIFIER
            type == T.QUOTED_IDENT -> SqlColors.QUOTED_IDENTIFIER
            type == T.STRING || type == T.DOLLAR_STRING -> SqlColors.STRING
            type == T.NUMBER -> SqlColors.NUMBER
            type == T.LINE_COMMENT -> SqlColors.COMMENT
            type == T.BLOCK_COMMENT -> SqlColors.BLOCK_COMMENT
            type == T.OPERATOR || type == T.EQ || type == T.CAST || type == T.ASTERISK -> SqlColors.OPERATOR
            type == T.LPAREN || type == T.RPAREN -> SqlColors.PARENS
            type == T.COMMA -> SqlColors.COMMA
            type == T.DOT -> SqlColors.DOT
            type == T.PARAMETER -> SqlColors.PARAMETER
            type == T.BAD_CHARACTER -> SqlColors.BAD
            else -> null
        }
    )
}

class SqlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = SqlSyntaxHighlighter()
}
