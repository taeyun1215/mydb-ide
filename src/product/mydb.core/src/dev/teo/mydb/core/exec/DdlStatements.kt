package dev.teo.mydb.core.exec

/**
 * What a DDL statement may have changed in the model, so that only those schemas are introspected again
 * after it ran.
 *
 * [schemas] are the schema qualifiers named in the statement (`maum.staff` → `maum`); they are
 * candidates, to be matched against the known schemas, as a qualifier can also be a table or alias.
 * Unqualified objects live in the session's current schema, which the caller always adds.
 * [schemaList] is set when schemas themselves were created, dropped or renamed.
 */
data class DdlChange(val schemas: Set<String>, val schemaList: Boolean)

object DdlStatements {
    private val HEADS = setOf("create", "alter", "drop", "rename", "comment")
    private val SCHEMA_KINDS = setOf("schema", "database")

    /** The change made by [sql], or null when it is not DDL (queries, DML, session commands, temporary tables). */
    fun change(sql: String): DdlChange? {
        val tokens = tokens(sql)
        val words = tokens.filter { it.kind == Kind.WORD }.map { it.text.lowercase() }
        val head = words.firstOrNull() ?: return null
        if (head !in HEADS) return null
        if (head == "comment" && words.getOrNull(1) != "on") return null
        // the object kind follows the head and its modifiers: `create or replace temporary view`, `drop materialized view`
        val kinds = words.drop(1).take(4)
        if (head == "create" && ("temporary" in kinds || "temp" in kinds)) return null
        val schemaList = head != "comment" && kinds.firstOrNull { it !in MODIFIERS } in SCHEMA_KINDS

        val schemas = linkedSetOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind != Kind.IDENT && t.kind != Kind.WORD) { i++; continue }
            // a qualified name: ident (. ident)+ — its leading parts may be a schema (or database.schema)
            val parts = mutableListOf(t.text)
            var j = i + 1
            while (j + 1 < tokens.size && tokens[j].kind == Kind.DOT && tokens[j + 1].kind.isName) { parts += tokens[j + 1].text; j += 2 }
            if (parts.size >= 2) schemas += parts[0]
            if (parts.size >= 3) schemas += parts[1]
            i = j
        }
        // `alter table t set schema s`: the table moves to s
        words.windowed(3).filter { it[0] == "set" && it[1] == "schema" }.forEach { w ->
            tokens.firstOrNull { tok -> tok.text.equals(w[2], ignoreCase = true) && tok.kind.isName }?.let { schemas += it.text }
        }
        return DdlChange(schemas, schemaList)
    }

    private val MODIFIERS = setOf("or", "replace", "unique", "materialized", "global", "local", "unlogged", "recursive",
        "if", "not", "exists", "only", "concurrently", "definer", "algorithm", "sql", "security", "trusted", "procedural")

    private enum class Kind { WORD, IDENT, DOT, OTHER; val isName get() = this == WORD || this == IDENT }
    /** WORD: unquoted identifier or keyword; IDENT: quoted identifier (text without the quotes). */
    private data class Token(val kind: Kind, val text: String)

    /** Tokens outside string literals, dollar-quoted bodies and comments. */
    private fun tokens(sql: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        val n = sql.length
        while (i < n) {
            val ch = sql[i]
            when {
                ch == '\'' -> {
                    i++
                    while (i < n) {
                        if (sql[i] == '\'') { if (i + 1 < n && sql[i + 1] == '\'') { i += 2; continue } else break }
                        i++
                    }
                    i++
                }
                ch == '"' || ch == '`' -> {
                    val s = ++i
                    while (i < n && sql[i] != ch) i++
                    out += Token(Kind.IDENT, sql.substring(s, minOf(i, n)))
                    i++
                }
                ch == '$' && i + 1 < n && (sql[i + 1] == '$' || sql[i + 1].isLetter() || sql[i + 1] == '_') -> {
                    val close = sql.indexOf('$', i + 1)
                    val tag = if (close > 0) sql.substring(i, close + 1) else ""
                    if (tag.isNotEmpty() && tag.drop(1).dropLast(1).all { it.isLetterOrDigit() || it == '_' }) {
                        val e = sql.indexOf(tag, close + 1)
                        i = if (e < 0) n else e + tag.length
                    } else i++
                }
                ch == '-' && i + 1 < n && sql[i + 1] == '-' -> { while (i < n && sql[i] != '\n') i++ }
                ch == '#' -> { while (i < n && sql[i] != '\n') i++ } // MySQL line comment
                ch == '/' && i + 1 < n && sql[i + 1] == '*' -> { val e = sql.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                ch == '.' -> { out += Token(Kind.DOT, "."); i++ }
                ch.isLetter() || ch == '_' -> {
                    val s = i
                    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                    out += Token(Kind.WORD, sql.substring(s, i))
                }
                ch.isWhitespace() -> i++
                else -> { out += Token(Kind.OTHER, ch.toString()); i++ }
            }
        }
        return out
    }
}
