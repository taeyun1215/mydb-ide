package dev.teo.mydb.plugin.sql

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import dev.teo.mydb.plugin.sql.SqlElementTypes as E
import dev.teo.mydb.plugin.sql.SqlTokenTypes as T

/**
 * Error-tolerant recursive descent parser for PostgreSQL. Every statement ends at `;` or the end of
 * the file; anything not understood is consumed into the current statement with an error marker,
 * so one broken statement never affects the next one.
 */
class SqlParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val m = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType == T.SEMICOLON) { builder.advanceLexer(); continue }
            Grammar(builder).statement()
        }
        m.done(root)
        return builder.treeBuilt
    }
}

private class Grammar(private val b: PsiBuilder) {
    private fun at(t: IElementType) = b.tokenType == t
    private fun atKw(word: String): Boolean { val k = T.KEYWORDS[word] ?: return false; return b.tokenType === k }
    private fun atAnyKw(vararg words: String) = words.any(::atKw)
    private fun eat(t: IElementType): Boolean = if (at(t)) { b.advanceLexer(); true } else false
    private fun eatKw(word: String): Boolean = if (atKw(word)) { b.advanceLexer(); true } else false
    private fun eatKws(vararg words: String) = words.all { eatKw(it) }
    private fun expectKw(word: String) { if (!eatKw(word)) b.error("${word.uppercase()} expected") }
    private fun expect(t: IElementType, what: String) { if (!eat(t)) b.error("$what expected") }
    private fun atEnd() = b.eof() || at(T.SEMICOLON)
    private fun atIdent() = b.tokenType in T.IDENTIFIERS || b.tokenType in T.SOFT_KEYWORDS

    fun statement() {
        val m = b.mark()
        val type = when {
            atKw("with") -> { query(); E.SELECT_STATEMENT }
            atKw("select") || at(T.LPAREN) || atKw("values") -> { query(); E.SELECT_STATEMENT }
            atKw("insert") -> { insert(); E.INSERT_STATEMENT }
            atKw("update") -> { update(); E.UPDATE_STATEMENT }
            atKw("delete") -> { delete(); E.DELETE_STATEMENT }
            atKw("create") -> create()
            atKw("alter") && b.lookAhead(1) === T.kw("table") -> { alterTable(); E.ALTER_TABLE_STATEMENT }
            atKw("drop") -> { drop(); E.DROP_STATEMENT }
            atKw("explain") -> { explain(); E.EXPLAIN_STATEMENT }
            else -> { other(); E.OTHER_STATEMENT }
        }
        if (!atEnd()) {
            val err = b.mark()
            while (!atEnd()) b.advanceLexer()
            err.error("Unexpected tokens")
        }
        m.done(type)
        eat(T.SEMICOLON)
    }

    // ---- queries --------------------------------------------------------------------------------

    /** WITH … select [union select]… [order by] [limit] */
    fun query() {
        val q = b.mark()
        if (atKw("with")) withClause()
        selectCore()
        while (atAnyKw("union", "intersect", "except")) {
            b.advanceLexer(); eatKw("all") || eatKw("distinct")
            selectCore()
        }
        if (atKw("order")) orderBy()
        limit()
        if (atKw("for")) { while (!atEnd() && !at(T.RPAREN)) b.advanceLexer() }
        q.done(E.QUERY_EXPRESSION)
    }

    private fun withClause() {
        val m = b.mark()
        expectKw("with"); eatKw("recursive")
        do {
            val cte = b.mark()
            if (atIdent()) { aliasDef() } else b.error("CTE name expected")
            if (at(T.LPAREN)) columnList()
            expectKw("as")
            eatKw("not"); eatKw("materialized")
            subquery()
            cte.done(E.CTE)
        } while (eat(T.COMMA))
        m.done(E.WITH_CLAUSE)
    }

    private fun selectCore() {
        when {
            at(T.LPAREN) -> { subquery(); return }
            atKw("values") -> { valuesClause(); return }
        }
        val sel = b.mark()
        expectKw("select")
        if (eatKw("distinct")) { if (eatKw("on")) parenExpressionList() } else eatKw("all")
        if (!atEnd() && !atAnyKw("from", "where", "group", "order", "limit", "union", "intersect", "except") && !at(T.RPAREN)) {
            do selectItem() while (eat(T.COMMA))
        }
        sel.done(E.SELECT_CLAUSE)
        if (atKw("into")) { b.advanceLexer(); eatKw("temporary") || eatKw("temp"); eatKw("table"); tableRef() }
        if (atKw("from")) fromClause()
        if (atKw("where")) clause(E.WHERE_CLAUSE) { b.advanceLexer(); expression() }
        if (atKw("group")) clause(E.GROUP_BY_CLAUSE) { b.advanceLexer(); expectKw("by"); expressionList() }
        if (atKw("having")) clause(E.HAVING_CLAUSE) { b.advanceLexer(); expression() }
        if (atKw("window")) { while (!atEnd() && !atAnyKw("order", "limit", "union", "intersect", "except") && !at(T.RPAREN)) b.advanceLexer() }
    }

    private fun selectItem() {
        val m = b.mark()
        if (at(T.ASTERISK)) b.advanceLexer() else expression()
        if (eatKw("as")) { if (atIdent()) aliasDef() else b.error("Alias expected") }
        else if (b.tokenType in T.IDENTIFIERS) aliasDef()
        m.done(E.SELECT_ITEM)
    }

    private fun fromClause() = clause(E.FROM_CLAUSE) {
        expectKw("from")
        fromItemWithJoins()
        while (eat(T.COMMA)) fromItemWithJoins()
    }

    private fun fromItemWithJoins() {
        fromItem()
        while (atJoin()) {
            val j = b.mark()
            eatKw("natural")
            if (atAnyKw("left", "right", "full")) { b.advanceLexer(); eatKw("outer") }
            else if (atKw("inner") || atKw("cross")) b.advanceLexer()
            expectKw("join")
            fromItem()
            when {
                atKw("on") -> { val c = b.mark(); b.advanceLexer(); expression(); c.done(E.JOIN_CONDITION) }
                atKw("using") -> { b.advanceLexer(); columnList() }
            }
            j.done(E.JOIN_CLAUSE)
        }
    }

    private fun atJoin() = atAnyKw("join", "inner", "left", "right", "full", "cross", "natural")

    private fun fromItem() {
        eatKw("lateral"); eatKw("only")
        when {
            at(T.LPAREN) -> {
                if (b.lookAhead(1) === T.kw("select") || b.lookAhead(1) === T.kw("with") || b.lookAhead(1) === T.kw("values")) subquery()
                else { b.advanceLexer(); fromItemWithJoins(); expect(T.RPAREN, "')'") }
            }
            atIdent() -> {
                // function in FROM (generate_series(…)) or a table reference
                if (b.lookAhead(1) == T.LPAREN) functionCall() else tableRef()
            }
            else -> { b.error("Table expected"); return }
        }
        tableAlias()
    }

    private fun tableAlias() {
        if (eatKw("as")) { if (atIdent()) aliasDef() else b.error("Alias expected") }
        else if (b.tokenType in T.IDENTIFIERS || (b.tokenType in T.SOFT_KEYWORDS && !atJoinOrClauseKeyword())) aliasDef()
        else return
        if (at(T.LPAREN)) columnList()
    }

    private fun atJoinOrClauseKeyword() = atJoin() || atAnyKw("on", "using", "where", "group", "order", "limit", "offset", "having",
        "union", "intersect", "except", "window", "for", "set", "returning", "values", "default", "do")

    private fun subquery() {
        val m = b.mark()
        expect(T.LPAREN, "'('")
        if (atKw("select") || atKw("with") || atKw("values") || at(T.LPAREN)) query() else b.error("Query expected")
        expect(T.RPAREN, "')'")
        m.done(E.SUBQUERY)
    }

    private fun valuesClause() = clause(E.VALUES_CLAUSE) {
        expectKw("values")
        do parenExpressionList() while (eat(T.COMMA))
    }

    private fun orderBy() = clause(E.ORDER_BY_CLAUSE) {
        expectKw("order"); expectKw("by")
        do {
            expression()
            eatKw("asc") || eatKw("desc")
            if (atKw("using")) { b.advanceLexer(); b.advanceLexer() }
            if (eatKw("nulls")) eatKw("first") || eatKw("last")
        } while (eat(T.COMMA))
    }

    private fun limit() {
        if (!atAnyKw("limit", "offset", "fetch")) return
        clause(E.LIMIT_CLAUSE) {
            while (atAnyKw("limit", "offset", "fetch")) {
                when {
                    eatKw("limit") -> { if (!eatKw("all")) expression() }
                    eatKw("offset") -> { expression(); eatKw("rows") || eatKw("row") }
                    eatKw("fetch") -> { eatKw("first") || eatKw("next"); if (!atKw("row") && !atKw("rows")) expression(); eatKw("rows") || eatKw("row"); eatKw("only") }
                }
            }
        }
    }

    // ---- DML -----------------------------------------------------------------------------------

    private fun insert() {
        if (atKw("with")) withClause()
        expectKw("insert"); expectKw("into")
        tableRef(); tableAlias()
        if (at(T.LPAREN) && b.lookAhead(1) != T.kw("select")) columnList()
        when {
            atKw("default") -> { b.advanceLexer(); expectKw("values") }
            atKw("values") -> valuesClause()
            else -> query()
        }
        if (atKw("on")) { while (!atEnd() && !atKw("returning")) b.advanceLexer() }
        returning()
    }

    private fun update() {
        expectKw("update"); eatKw("only")
        tableRef(); tableAlias()
        clause(E.SET_CLAUSE) {
            expectKw("set")
            do {
                if (at(T.LPAREN)) columnList() else columnRef()
                expect(T.EQ, "'='")
                expression()
            } while (eat(T.COMMA))
        }
        if (atKw("from")) fromClause()
        if (atKw("where")) clause(E.WHERE_CLAUSE) { b.advanceLexer(); expression() }
        returning()
    }

    private fun delete() {
        expectKw("delete"); expectKw("from"); eatKw("only")
        tableRef(); tableAlias()
        if (atKw("using")) clause(E.FROM_CLAUSE) { b.advanceLexer(); fromItemWithJoins(); while (eat(T.COMMA)) fromItemWithJoins() }
        if (atKw("where")) clause(E.WHERE_CLAUSE) { b.advanceLexer(); expression() }
        returning()
    }

    private fun returning() {
        if (atKw("returning")) clause(E.RETURNING_CLAUSE) { b.advanceLexer(); do selectItem() while (eat(T.COMMA)) }
    }

    // ---- DDL -----------------------------------------------------------------------------------

    private fun create(): IElementType {
        expectKw("create")
        if (atKw("or")) { b.advanceLexer(); expectKw("replace") }
        eatKw("temporary") || eatKw("temp") || eatKw("unlogged")
        if (atKw("table")) {
            b.advanceLexer()
            if (eatKw("if")) { expectKw("not"); expectKw("exists") }
            val def = b.mark(); qualifiedName(); def.done(E.TABLE_DEF)
            if (at(T.LPAREN)) {
                b.advanceLexer()
                if (!at(T.RPAREN)) do tableElement() while (eat(T.COMMA))
                expect(T.RPAREN, "')'")
            } else if (eatKw("as")) query()
            while (!atEnd()) b.advanceLexer()
            return E.CREATE_TABLE_STATEMENT
        }
        if (atKw("view") || (atKw("materialized") && b.lookAhead(1) === T.kw("view"))) {
            eatKw("materialized"); b.advanceLexer()
            if (eatKw("if")) { expectKw("not"); expectKw("exists") }
            val def = b.mark(); qualifiedName(); def.done(E.TABLE_DEF)
            if (at(T.LPAREN)) columnList()
            expectKw("as")
            query()
            return E.OTHER_STATEMENT
        }
        if (atKw("unique") || atKw("index")) {
            eatKw("unique"); expectKw("index"); eatKw("concurrently")
            if (eatKw("if")) { expectKw("not"); expectKw("exists") }
            if (atIdent() && !atKw("on")) b.advanceLexer()
            expectKw("on"); eatKw("only")
            tableRef()
            if (atKw("using")) { b.advanceLexer(); b.advanceLexer() }
            if (at(T.LPAREN)) parenExpressionList()
            while (!atEnd()) { if (atKw("where")) clause(E.WHERE_CLAUSE) { b.advanceLexer(); expression() } else b.advanceLexer() }
            return E.OTHER_STATEMENT
        }
        other()
        return E.OTHER_STATEMENT
    }

    private fun tableElement() {
        if (atAnyKw("constraint", "primary", "unique", "foreign", "check", "exclude")) { constraint(); return }
        if (atKw("like")) { b.advanceLexer(); tableRef(); while (!at(T.COMMA) && !at(T.RPAREN) && !atEnd()) b.advanceLexer(); return }
        val col = b.mark()
        if (atIdent()) b.advanceLexer() else b.error("Column name expected")
        typeElement()
        while (!at(T.COMMA) && !at(T.RPAREN) && !atEnd()) {
            when {
                atKw("references") -> { b.advanceLexer(); tableRef(); if (at(T.LPAREN)) columnList() }
                atKw("default") -> { b.advanceLexer(); expression() }
                atKw("check") -> { b.advanceLexer(); parenExpressionList() }
                at(T.LPAREN) -> skipParens()
                else -> b.advanceLexer()
            }
        }
        col.done(E.COLUMN_DEF)
    }

    private fun constraint() {
        val m = b.mark()
        if (eatKw("constraint")) { if (atIdent()) b.advanceLexer() }
        when {
            atKw("foreign") -> { b.advanceLexer(); expectKw("key"); columnList(); expectKw("references"); tableRef(); if (at(T.LPAREN)) columnList() }
            atKw("primary") -> { b.advanceLexer(); expectKw("key"); if (at(T.LPAREN)) columnList() }
            atKw("unique") -> { b.advanceLexer(); if (at(T.LPAREN)) columnList() }
            atKw("check") -> { b.advanceLexer(); parenExpressionList() }
        }
        while (!at(T.COMMA) && !at(T.RPAREN) && !atEnd()) { if (at(T.LPAREN)) skipParens() else b.advanceLexer() }
        m.done(E.CONSTRAINT_DEF)
    }

    private fun alterTable() {
        expectKw("alter"); expectKw("table")
        if (eatKw("if")) expectKw("exists")
        eatKw("only")
        tableRef()
        while (!atEnd()) {
            when {
                atKw("references") -> { b.advanceLexer(); tableRef(); if (at(T.LPAREN)) columnList() }
                atKw("add") && b.lookAhead(1) != T.kw("constraint") && b.lookAhead(1) != T.kw("primary") && b.lookAhead(1) != T.kw("unique") && b.lookAhead(1) != T.kw("foreign") && b.lookAhead(1) != T.kw("check") -> {
                    b.advanceLexer(); eatKw("column"); if (eatKw("if")) { expectKw("not"); expectKw("exists") }
                    val col = b.mark()
                    if (atIdent()) b.advanceLexer()
                    typeElement()
                    while (!at(T.COMMA) && !atEnd()) { if (atKw("references")) { b.advanceLexer(); tableRef() } else b.advanceLexer() }
                    col.done(E.COLUMN_DEF)
                }
                else -> b.advanceLexer()
            }
        }
    }

    private fun drop() {
        expectKw("drop")
        val isTableLike = atAnyKw("table", "view", "sequence", "index") || atKw("materialized")
        eatKw("materialized")
        b.advanceLexer()
        if (eatKw("if")) expectKw("exists")
        if (isTableLike) { do tableRef() while (eat(T.COMMA)) }
        while (!atEnd()) b.advanceLexer()
    }

    private fun explain() {
        expectKw("explain")
        if (at(T.LPAREN)) skipParens() else { eatKw("analyze") || eatKw("analyse"); eatKw("verbose") }
        when {
            atKw("select") || atKw("with") || at(T.LPAREN) -> query()
            atKw("insert") -> insert()
            atKw("update") -> update()
            atKw("delete") -> delete()
            else -> other()
        }
    }

    /** Unknown statement: tokens are kept, table names after typical keywords become references. */
    private fun other() {
        while (!atEnd()) {
            when {
                atAnyKw("table", "into", "references") && (b.lookAhead(1) in T.IDENTIFIERS) -> { b.advanceLexer(); tableRef() }
                else -> b.advanceLexer()
            }
        }
    }

    private fun typeElement() {
        val m = b.mark()
        if (atAnyKw("double", "character", "timestamp", "time") || atIdent() || b.tokenType in T.ALL_KEYWORDS) {
            // multi-word types: double precision, character varying, timestamp with time zone
            b.advanceLexer()
            while (atAnyKw("precision", "varying", "with", "without", "time", "zone") || (at(T.DOT) && b.lookAhead(1) in T.IDENTIFIERS)) {
                if (atKw("with") && b.lookAhead(1) !== T.kw("time")) break
                b.advanceLexer()
            }
            if (at(T.LPAREN)) skipParens()
            while (at(T.LBRACKET)) { b.advanceLexer(); eat(T.NUMBER); expect(T.RBRACKET, "']'") }
        } else b.error("Type expected")
        m.done(E.TYPE_ELEMENT)
    }

    // ---- names ---------------------------------------------------------------------------------

    fun tableRef() {
        val m = b.mark()
        if (!qualifiedName()) { m.drop(); b.error("Table name expected"); return }
        m.done(E.TABLE_REF)
    }

    private fun qualifiedName(): Boolean {
        if (!atIdent()) return false
        b.advanceLexer()
        while (at(T.DOT)) {
            b.advanceLexer()
            if (atIdent() || b.tokenType in T.ALL_KEYWORDS) b.advanceLexer() else { b.error("Name expected"); break }
        }
        return true
    }

    private fun aliasDef() {
        val m = b.mark(); b.advanceLexer(); m.done(E.ALIAS_DEF)
    }

    private fun columnList() {
        val m = b.mark()
        expect(T.LPAREN, "'('")
        if (!at(T.RPAREN)) do { if (atIdent()) columnRef() else b.error("Column expected") } while (eat(T.COMMA))
        expect(T.RPAREN, "')'")
        m.done(E.COLUMN_LIST)
    }

    private fun columnRef() {
        val m = b.mark()
        qualifiedName()
        m.done(E.COLUMN_REF)
    }

    // ---- expressions ---------------------------------------------------------------------------

    private fun expressionList() { do expression() while (eat(T.COMMA)) }

    private fun parenExpressionList() {
        val m = b.mark()
        expect(T.LPAREN, "'('")
        if (!at(T.RPAREN)) expressionList()
        expect(T.RPAREN, "')'")
        m.done(E.PAREN_EXPRESSION)
    }

    private val BINARY: TokenSet = TokenSet.create(T.OPERATOR, T.EQ, T.ASTERISK)

    fun expression() {
        var left = b.mark()
        unary()
        while (true) {
            when {
                b.tokenType in BINARY || atAnyKw("and", "or", "like", "ilike") -> { b.advanceLexer(); unary() }
                atKw("not") && (b.lookAhead(1) === T.kw("like") || b.lookAhead(1) === T.kw("ilike") || b.lookAhead(1) === T.kw("in") || b.lookAhead(1) === T.kw("between")) -> { b.advanceLexer(); continue }
                atKw("is") -> { b.advanceLexer(); eatKw("not"); if (!eatKw("null") && !eatKw("true") && !eatKw("false")) { if (eatKw("distinct")) { expectKw("from"); unary() } } }
                atKw("in") -> { b.advanceLexer(); if (at(T.LPAREN) && (b.lookAhead(1) === T.kw("select") || b.lookAhead(1) === T.kw("with"))) subquery() else parenExpressionList() }
                atKw("between") -> { b.advanceLexer(); eatKw("symmetric"); unary(); expectKw("and"); unary() }
                atKw("similar") -> { b.advanceLexer(); expectKw("to"); unary() }
                at(T.CAST) -> { b.advanceLexer(); typeElement() }
                at(T.LBRACKET) -> { b.advanceLexer(); expression(); if (at(T.OPERATOR) && b.tokenText == ":") b.advanceLexer(); expect(T.RBRACKET, "']'") }
                atKw("collate") -> { b.advanceLexer(); qualifiedName() }
                else -> break
            }
            left.done(E.BINARY_EXPRESSION)
            left = left.precede()
        }
        left.drop()
    }

    private fun unary() {
        when {
            atKw("not") -> { b.advanceLexer(); unary() }
            at(T.OPERATOR) && (b.tokenText == "-" || b.tokenText == "+" || b.tokenText == "~") -> { b.advanceLexer(); unary() }
            else -> primary()
        }
    }

    private fun primary() {
        val m = b.mark()
        when {
            b.tokenType == T.NUMBER || b.tokenType in T.STRINGS || b.tokenType == T.PARAMETER || atAnyKw("null", "true", "false", "default",
                "current_date", "current_time", "current_timestamp", "current_user", "session_user", "localtime", "localtimestamp") -> {
                b.advanceLexer(); m.done(E.LITERAL)
            }
            atKw("interval") || ((atAnyKw("timestamp", "date", "time") || b.tokenType == T.IDENT) && b.lookAhead(1) == T.STRING) -> {
                b.advanceLexer(); b.advanceLexer(); m.done(E.LITERAL)
            }
            atKw("exists") -> { b.advanceLexer(); subquery(); m.done(E.EXPRESSION) }
            atKw("case") -> { caseExpression(); m.done(E.CASE_EXPRESSION) }
            atKw("cast") -> { b.advanceLexer(); expect(T.LPAREN, "'('"); expression(); expectKw("as"); typeElement(); expect(T.RPAREN, "')'"); m.done(E.FUNCTION_CALL) }
            atKw("array") -> {
                b.advanceLexer()
                if (at(T.LBRACKET)) { b.advanceLexer(); if (!at(T.RBRACKET)) expressionList(); expect(T.RBRACKET, "']'") } else subquery()
                m.done(E.EXPRESSION)
            }
            atAnyKw("any", "some", "all") && b.lookAhead(1) == T.LPAREN -> {
                b.advanceLexer()
                if (b.lookAhead(1) === T.kw("select")) subquery() else parenExpressionList()
                m.done(E.FUNCTION_CALL)
            }
            at(T.LPAREN) -> {
                if (b.lookAhead(1) === T.kw("select") || b.lookAhead(1) === T.kw("with")) { m.drop(); subquery() }
                else { b.advanceLexer(); expressionList(); expect(T.RPAREN, "')'"); m.done(E.PAREN_EXPRESSION) }
            }
            atIdent() || (b.tokenType in T.ALL_KEYWORDS && b.lookAhead(1) == T.LPAREN && !atKw("in") && !atKw("values")) -> {
                if (b.lookAhead(1) == T.LPAREN) { m.drop(); functionCall() }
                else {
                    qualifiedName()
                    if (at(T.DOT) && b.lookAhead(1) == T.ASTERISK) { b.advanceLexer(); b.advanceLexer() }
                    m.done(E.COLUMN_REF)
                }
            }
            at(T.ASTERISK) -> { b.advanceLexer(); m.done(E.EXPRESSION) }
            else -> { m.drop(); b.error("Expression expected"); if (!atEnd() && !at(T.RPAREN) && !at(T.COMMA)) b.advanceLexer() }
        }
    }

    private fun functionCall() {
        val m = b.mark()
        if (!qualifiedName()) b.advanceLexer() // keyword-named functions: left, replace, …
        expect(T.LPAREN, "'('")
        eatKw("distinct") || eatKw("all")
        if (at(T.ASTERISK)) b.advanceLexer() else if (!at(T.RPAREN)) expressionList()
        if (atKw("order")) orderBy()
        expect(T.RPAREN, "')'")
        if (atKw("within")) { b.advanceLexer(); expectKw("group"); skipParens() }
        if (atKw("filter")) { b.advanceLexer(); skipParens() }
        if (atKw("over")) { b.advanceLexer(); if (at(T.LPAREN)) skipParens() else b.advanceLexer() }
        m.done(E.FUNCTION_CALL)
    }

    private fun caseExpression() {
        expectKw("case")
        if (!atKw("when")) expression()
        while (eatKw("when")) { expression(); expectKw("then"); expression() }
        if (eatKw("else")) expression()
        expectKw("end")
    }

    private fun skipParens() {
        var depth = 0
        do {
            if (at(T.LPAREN)) depth++ else if (at(T.RPAREN)) depth--
            b.advanceLexer()
        } while (depth > 0 && !b.eof())
    }

    private inline fun clause(type: IElementType, body: () -> Unit) {
        val m = b.mark(); body(); m.done(type)
    }
}
