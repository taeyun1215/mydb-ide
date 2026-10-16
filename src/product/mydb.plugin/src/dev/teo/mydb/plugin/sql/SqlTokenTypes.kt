package dev.teo.mydb.plugin.sql

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class SqlTokenType(debugName: String) : IElementType(debugName, SqlLanguage) {
    override fun toString() = "SqlToken." + super.toString()
}

class SqlKeywordType(val keyword: String) : IElementType(keyword.uppercase(), SqlLanguage)

object SqlTokenTypes {
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER
    val LINE_COMMENT = SqlTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = SqlTokenType("BLOCK_COMMENT")
    val IDENT = SqlTokenType("IDENT")
    val QUOTED_IDENT = SqlTokenType("QUOTED_IDENT")
    val STRING = SqlTokenType("STRING")
    val DOLLAR_STRING = SqlTokenType("DOLLAR_STRING")
    val NUMBER = SqlTokenType("NUMBER")
    val PARAMETER = SqlTokenType("PARAMETER")
    val LPAREN = SqlTokenType("(")
    val RPAREN = SqlTokenType(")")
    val LBRACKET = SqlTokenType("[")
    val RBRACKET = SqlTokenType("]")
    val COMMA = SqlTokenType(",")
    val DOT = SqlTokenType(".")
    val SEMICOLON = SqlTokenType(";")
    val CAST = SqlTokenType("::")
    val ASTERISK = SqlTokenType("*")
    val EQ = SqlTokenType("=")
    val OPERATOR = SqlTokenType("OPERATOR")

    /** Keywords used by the grammar or highlighted; unreserved ones may also act as identifiers. */
    private val KEYWORD_LIST = listOf(
        "select", "from", "where", "group", "by", "having", "order", "limit", "offset", "fetch", "first", "next", "rows", "row", "only",
        "join", "inner", "left", "right", "full", "outer", "cross", "natural", "lateral", "on", "using", "as",
        "union", "intersect", "except", "all", "distinct", "with", "recursive",
        "insert", "into", "values", "default", "update", "set", "delete", "returning", "conflict", "do", "nothing", "merge", "matched",
        "create", "alter", "drop", "truncate", "table", "view", "materialized", "index", "unique", "sequence", "schema", "database",
        "function", "procedure", "trigger", "type", "extension", "if", "exists", "not", "cascade", "restrict", "temporary", "temp",
        "replace", "add", "column", "constraint", "primary", "foreign", "key", "references", "check", "rename", "to", "owner",
        "and", "or", "is", "null", "in", "between", "like", "ilike", "similar", "case", "when", "then", "else", "end", "cast",
        "true", "false", "asc", "desc", "nulls", "last", "over", "partition", "window", "filter", "within",
        "begin", "commit", "rollback", "transaction", "savepoint", "release", "explain", "analyze", "analyse", "verbose",
        "grant", "revoke", "comment", "vacuum", "copy", "show", "reset", "any", "some", "array", "interval", "collate", "escape",
        "current_date", "current_time", "current_timestamp", "current_user", "session_user", "localtime", "localtimestamp",
        "for", "of", "share", "nowait", "skip", "locked", "tablespace", "unlogged", "generated", "always", "identity", "stored",
        "deferrable", "initially", "deferred", "immediate", "no", "action", "match", "simple", "language", "returns", "setof",
        "zone", "time", "timestamp", "with", "without", "varying", "precision", "double", "character",
    ).distinct()

    val KEYWORDS: Map<String, SqlKeywordType> = KEYWORD_LIST.associateWith { SqlKeywordType(it) }
    fun kw(word: String): SqlKeywordType = KEYWORDS[word] ?: error("unknown keyword $word")

    /** Reserved words that can never be identifiers (PostgreSQL reserved set ∩ our list). */
    private val RESERVED = setOf(
        "select", "from", "where", "group", "having", "order", "limit", "offset", "fetch", "join", "inner", "left", "right", "full",
        "outer", "cross", "natural", "lateral", "on", "using", "as", "union", "intersect", "except", "all", "distinct", "with",
        "into", "default", "returning", "create", "table", "unique", "not", "and", "or", "is", "null", "in", "case", "when", "then",
        "else", "end", "cast", "true", "false", "asc", "desc", "window", "primary", "foreign", "references", "check", "constraint",
        "analyse", "analyze", "any", "some", "array", "collate", "for", "grant", "only", "to", "initially", "deferrable",
        "current_date", "current_time", "current_timestamp", "current_user", "session_user", "localtime", "localtimestamp", "column",
        "do", "fetch", "between", "like", "ilike", "similar", "over", "filter", "within", "set", "values", "update", "delete",
        "insert", "alter", "drop", "truncate", "by",
    )

    val RESERVED_KEYWORDS: TokenSet = TokenSet.create(*RESERVED.map { KEYWORDS.getValue(it) }.toTypedArray())
    val ALL_KEYWORDS: TokenSet = TokenSet.create(*KEYWORDS.values.toTypedArray())
    /** Keywords that may be used where an identifier is expected (`name`, `key`, `type`, …). */
    val SOFT_KEYWORDS: TokenSet = TokenSet.andNot(ALL_KEYWORDS, RESERVED_KEYWORDS)

    val IDENTIFIERS: TokenSet = TokenSet.create(IDENT, QUOTED_IDENT)
    val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    val STRINGS: TokenSet = TokenSet.create(STRING, DOLLAR_STRING)
    val WHITESPACES: TokenSet = TokenSet.WHITE_SPACE
}
