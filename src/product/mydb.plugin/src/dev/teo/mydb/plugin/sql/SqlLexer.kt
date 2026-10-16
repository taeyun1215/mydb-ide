package dev.teo.mydb.plugin.sql

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import dev.teo.mydb.plugin.sql.SqlTokenTypes as T

/** PostgreSQL lexer. Restartable at any token start (state 0 everywhere; dollar bodies are single tokens). */
class SqlLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.tokenEnd = startOffset
        advance()
    }

    override fun getState() = 0
    override fun getTokenType() = tokenType
    override fun getTokenStart() = tokenStart
    override fun getTokenEnd() = tokenEnd
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = end

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= end) { tokenType = null; return }
        val c = buffer[tokenStart]
        var i = tokenStart
        tokenType = when {
            c.isWhitespace() -> { while (i < end && buffer[i].isWhitespace()) i++; T.WHITE_SPACE }
            c == '-' && at(i + 1) == '-' -> { while (i < end && buffer[i] != '\n') i++; T.LINE_COMMENT }
            c == '/' && at(i + 1) == '*' -> { i = blockComment(i); T.BLOCK_COMMENT }
            c == '\'' || ((c == 'e' || c == 'E' || c == 'n' || c == 'N' || c == 'b' || c == 'B' || c == 'x' || c == 'X') && at(i + 1) == '\'') -> {
                if (c != '\'') i++
                i = quoted(i, '\''); T.STRING
            }
            c == '"' -> { i = quoted(i, '"'); T.QUOTED_IDENT }
            // MySQL quotes identifiers with backticks
            c == '`' -> { i = quoted(i, '`'); T.QUOTED_IDENT }
            c == '$' && at(i + 1)?.isDigit() == true -> { i++; while (i < end && buffer[i].isDigit()) i++; T.PARAMETER }
            c == '$' -> dollar(i)?.let { i = it; T.DOLLAR_STRING } ?: run { i++; T.BAD_CHARACTER }
            c.isDigit() || (c == '.' && at(i + 1)?.isDigit() == true) -> { i = number(i); T.NUMBER }
            c.isLetter() || c == '_' -> {
                while (i < end && (buffer[i].isLetterOrDigit() || buffer[i] == '_' || buffer[i] == '$')) i++
                T.KEYWORDS[buffer.subSequence(tokenStart, i).toString().lowercase()] ?: T.IDENT
            }
            c == ':' && at(i + 1) == ':' -> { i += 2; T.CAST }
            c == ':' && at(i + 1)?.let { it.isLetter() || it == '_' } == true -> { i++; while (i < end && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++; T.PARAMETER }
            c == '?' -> { i++; T.PARAMETER }
            c == '(' -> { i++; T.LPAREN }
            c == ')' -> { i++; T.RPAREN }
            c == '[' -> { i++; T.LBRACKET }
            c == ']' -> { i++; T.RBRACKET }
            c == ',' -> { i++; T.COMMA }
            c == '.' -> { i++; T.DOT }
            c == ';' -> { i++; T.SEMICOLON }
            c == '*' -> { i++; T.ASTERISK }
            c == '=' && at(i + 1) != '>' -> { i++; T.EQ }
            c in OPERATOR_CHARS -> { while (i < end && buffer[i] in OPERATOR_CHARS) i++; T.OPERATOR }
            else -> { i++; T.BAD_CHARACTER }
        }
        tokenEnd = i
    }

    private fun at(i: Int): Char? = if (i < end) buffer[i] else null

    private fun quoted(from: Int, q: Char): Int {
        var i = from + 1
        while (i < end) {
            if (buffer[i] == q) { if (at(i + 1) == q) i += 2 else return i + 1 } else i++
        }
        return end
    }

    private fun blockComment(from: Int): Int {
        var depth = 0
        var i = from
        while (i < end) {
            if (buffer[i] == '/' && at(i + 1) == '*') { depth++; i += 2 }
            else if (buffer[i] == '*' && at(i + 1) == '/') { depth--; i += 2; if (depth == 0) return i }
            else i++
        }
        return end
    }

    private fun dollar(from: Int): Int? {
        var i = from + 1
        while (i < end && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++
        if (at(i) != '$') return null
        val tag = buffer.subSequence(from, i + 1).toString()
        val close = buffer.indexOf(tag, i + 1)
        return if (close < 0 || close >= end) end else close + tag.length
    }

    private fun number(from: Int): Int {
        var i = from
        while (i < end && buffer[i].isDigit()) i++
        if (at(i) == '.' && at(i + 1) != '.') { i++; while (i < end && buffer[i].isDigit()) i++ }
        if (at(i) == 'e' || at(i) == 'E') {
            val j = if (at(i + 1) == '+' || at(i + 1) == '-') i + 2 else i + 1
            if (at(j)?.isDigit() == true) { i = j; while (i < end && buffer[i].isDigit()) i++ }
        }
        return i
    }

    private fun CharSequence.indexOf(s: String, from: Int): Int = toString().indexOf(s, from)

    companion object {
        private const val OPERATOR_CHARS = "+-*/<>=~!@#%^&|`?"
    }
}
