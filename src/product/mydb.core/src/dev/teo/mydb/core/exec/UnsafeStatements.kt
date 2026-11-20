package dev.teo.mydb.core.exec

/**
 * DELETE / UPDATE without a top-level WHERE change every row; the console asks before running them
 * when the "confirm unsafe statements" setting is on.
 */
object UnsafeStatements {
    fun isUnsafe(sql: String): Boolean {
        val words = topLevelWords(sql)
        val first = words.firstOrNull() ?: return false
        // `with … delete …` keeps the check on the main statement
        val main = if (first == "with") words.firstOrNull { it == "delete" || it == "update" } ?: return false else first
        if (main != "delete" && main != "update") return false
        val body = words.dropWhile { it != main }
        if ("where" in body) return false
        // MySQL `update t a join u b on … set …`: the inner join limits the rows of t
        return !(main == "update" && innerJoinOn(body.takeWhile { it != "set" }))
    }

    private val OUTER = setOf("left", "right", "full", "outer", "cross", "natural")

    /** An inner join with an ON / USING condition among [words]. */
    fun innerJoinOn(words: List<String>): Boolean = words.indices.any { k ->
        words[k] == "join" && words.getOrNull(k - 1) !in OUTER && words.drop(k + 1).any { it == "on" || it == "using" }
    }

    /** Lower-case words outside quotes, comments and parentheses. */
    private fun topLevelWords(sql: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var depth = 0
        val n = sql.length
        while (i < n) {
            val ch = sql[i]
            when {
                ch == '\'' || ch == '"' -> { val q = ch; i++; while (i < n && sql[i] != q) i++; i++ }
                ch == '-' && i + 1 < n && sql[i + 1] == '-' -> { while (i < n && sql[i] != '\n') i++ }
                ch == '/' && i + 1 < n && sql[i + 1] == '*' -> { val e = sql.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                ch == '(' -> { depth++; i++ }
                ch == ')' -> { depth--; i++ }
                ch.isLetter() || ch == '_' -> {
                    val s = i
                    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                    if (depth == 0) out += sql.substring(s, i).lowercase()
                }
                else -> i++
            }
        }
        return out
    }
}
