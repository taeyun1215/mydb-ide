package dev.teo.mydb.core.exec

/** A statement inside a script; [range] excludes the terminating `;` and surrounding whitespace. */
data class ScriptStatement(val text: String, val range: IntRange) {
    val start get() = range.first
    val endExclusive get() = range.last + 1
}

/**
 * Splits SQL text into statements at top-level `;`, skipping quotes, quoted identifiers,
 * dollar-quoted bodies and comments (PostgreSQL lexical rules).
 */
object StatementSplitter {

    fun split(sql: String): List<ScriptStatement> {
        val result = mutableListOf<ScriptStatement>()
        var start = 0
        var i = 0
        val n = sql.length
        fun flush(end: Int) {
            var s = start
            var e = end
            while (s < e && sql[s].isWhitespace()) s++
            while (e > s && sql[e - 1].isWhitespace()) e--
            if (e > s && !isOnlyComments(sql.substring(s, e))) result += ScriptStatement(sql.substring(s, e), s until e)
        }
        while (i < n) {
            val ch = sql[i]
            when {
                ch == '\'' -> i = skipQuoted(sql, i, '\'')
                ch == '"' -> i = skipQuoted(sql, i, '"')
                ch == '-' && i + 1 < n && sql[i + 1] == '-' -> i = sql.indexOf('\n', i).let { if (it < 0) n else it }
                ch == '/' && i + 1 < n && sql[i + 1] == '*' -> i = skipBlockComment(sql, i)
                ch == '$' -> i = skipDollarQuoted(sql, i)
                ch == ';' -> { flush(i); start = i + 1; i++ }
                else -> i++
            }
        }
        flush(n)
        return result
    }

    private fun skipQuoted(s: String, from: Int, q: Char): Int {
        var i = from + 1
        while (i < s.length) {
            if (s[i] == q) {
                if (i + 1 < s.length && s[i + 1] == q) i += 2 else return i + 1
            } else i++
        }
        return s.length
    }

    private fun skipBlockComment(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            if (s.startsWith("/*", i)) { depth++; i += 2 }
            else if (s.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) return i }
            else i++
        }
        return s.length
    }

    private val DOLLAR_TAG = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$")

    private fun skipDollarQuoted(s: String, from: Int): Int {
        val m = DOLLAR_TAG.matchAt(s, from) ?: return from + 1
        // `$1` placeholders do not match because the tag must start with a letter or be empty
        val tag = m.value
        val end = s.indexOf(tag, m.range.last + 1)
        return if (end < 0) s.length else end + tag.length
    }

    private fun isOnlyComments(text: String): Boolean {
        var t = text.trim()
        while (t.isNotEmpty()) {
            t = when {
                t.startsWith("--") -> t.substringAfter('\n', "").trim()
                t.startsWith("/*") -> t.substringAfter("*/", "").trim()
                else -> return false
            }
        }
        return true
    }
}

/** Execution target candidates for the caret. */
sealed interface RunTarget {
    data class Statement(val statement: ScriptStatement) : RunTarget
    data class Script(val statements: List<ScriptStatement>) : RunTarget
}

enum class CaretInsidePolicy { ASK, SMALLEST, LARGEST }

object RunTargets {
    /**
     * Returns either a single target to run immediately or several candidates to choose from.
     * The caret is "inside" a statement when it lies within its range or right after its end;
     * on a boundary between statements (or when more than one statement exists and the policy is ASK)
     * the user chooses between the statement and the whole script.
     */
    fun resolve(sql: String, caret: Int, selection: IntRange? = null, policy: CaretInsidePolicy = CaretInsidePolicy.ASK): List<RunTarget> {
        val all = StatementSplitter.split(sql)
        if (selection != null && !selection.isEmpty()) {
            val text = sql.substring(selection.first, selection.last + 1)
            return listOf(RunTarget.Script(StatementSplitter.split(text).map { it.copy(range = it.range.first + selection.first..it.range.last + selection.first) }))
        }
        if (all.isEmpty()) return emptyList()
        if (all.size == 1) return listOf(RunTarget.Statement(all.single()))
        val current = all.firstOrNull { caret in it.start..it.endExclusive }
            ?: all.lastOrNull { it.endExclusive <= caret }
            ?: all.first()
        val atBoundary = caret > current.endExclusive // after the terminating `;`
        val candidates = listOf(RunTarget.Statement(current), RunTarget.Script(all))
        return when {
            policy == CaretInsidePolicy.SMALLEST -> candidates.take(1)
            policy == CaretInsidePolicy.LARGEST -> candidates.takeLast(1)
            atBoundary -> candidates
            else -> candidates.take(1)
        }
    }
}
