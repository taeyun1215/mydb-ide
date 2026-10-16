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
        return "where" !in words.dropWhile { it != main }
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
