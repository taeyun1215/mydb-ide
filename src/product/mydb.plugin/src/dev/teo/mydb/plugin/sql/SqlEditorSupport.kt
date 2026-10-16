package dev.teo.mydb.plugin.sql

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class SqlCommenter : Commenter {
    override fun getLineCommentPrefix() = "-- "
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix() = null
    override fun getCommentedBlockCommentSuffix() = null
}

class SqlBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(BracePair(SqlTokenTypes.LPAREN, SqlTokenTypes.RPAREN, false), BracePair(SqlTokenTypes.LBRACKET, SqlTokenTypes.RBRACKET, false))
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}
