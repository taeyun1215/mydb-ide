package dev.teo.mydb.core.transfer

import java.io.File

/** pg_dump output formats (`-F`). */
enum class DumpFormat(val flag: String, val title: String, val extension: String) {
    PLAIN("p", "Plain SQL", "sql"), CUSTOM("c", "Custom archive", "dump"), DIRECTORY("d", "Directory", ""), TAR("t", "Tar archive", "tar")
}

/** Where to connect: taken from the data source URL; the password goes in the environment, never on the command line. */
data class PgTarget(val host: String, val port: Int?, val user: String?, val database: String)

data class DumpOptions(
    val tool: String,
    val target: PgTarget,
    val output: String,
    val format: DumpFormat = DumpFormat.PLAIN,
    val schemas: List<String> = emptyList(),
    /** `schema.table` names; when set, only these tables are dumped. */
    val tables: List<String> = emptyList(),
    val schemaOnly: Boolean = false,
    val dataOnly: Boolean = false,
    val clean: Boolean = false,
    val ifExists: Boolean = false,
    val noOwner: Boolean = false,
    val inserts: Boolean = false,
    val extraArgs: String = "",
)

data class RestoreOptions(
    val tool: String,
    val target: PgTarget,
    val input: String,
    val clean: Boolean = false,
    val ifExists: Boolean = false,
    val noOwner: Boolean = false,
    val singleTransaction: Boolean = true,
    val extraArgs: String = "",
)

/**
 * Command lines of the PostgreSQL client tools for dump and restore. A plain SQL file is
 * restored with psql, an archive with pg_restore.
 */
object PgTools {
    private fun connection(t: PgTarget) = buildList {
        add("--host=${t.host}")
        t.port?.let { add("--port=$it") }
        t.user?.takeIf { it.isNotBlank() }?.let { add("--username=$it") }
        add("--no-password")
    }

    fun dump(o: DumpOptions): List<String> = buildList {
        add(o.tool)
        addAll(connection(o.target))
        add("--format=${o.format.flag}")
        if (o.schemaOnly) add("--schema-only")
        if (o.dataOnly) add("--data-only")
        if (o.clean) add("--clean")
        if (o.ifExists) add("--if-exists")
        if (o.noOwner) add("--no-owner")
        if (o.inserts) add("--inserts")
        if (o.tables.isNotEmpty()) o.tables.forEach { add("--table=$it") } else o.schemas.forEach { add("--schema=$it") }
        add("--file=${o.output}")
        addAll(splitArgs(o.extraArgs))
        add(o.target.database)
    }

    /** True when [input] is a plain SQL script (restored with psql). */
    fun isPlainSql(input: String) = File(input).isFile && File(input).extension.lowercase() == "sql"

    fun restore(o: RestoreOptions): List<String> = if (isPlainSql(o.input)) buildList {
        add(o.tool)
        addAll(connection(o.target))
        add("--dbname=${o.target.database}")
        if (o.singleTransaction) add("--single-transaction")
        add("--set=ON_ERROR_STOP=1")
        add("--file=${o.input}")
        addAll(splitArgs(o.extraArgs))
    } else buildList {
        add(o.tool)
        addAll(connection(o.target))
        add("--dbname=${o.target.database}")
        if (o.clean) add("--clean")
        if (o.ifExists) add("--if-exists")
        if (o.noOwner) add("--no-owner")
        if (o.singleTransaction) add("--single-transaction")
        addAll(splitArgs(o.extraArgs))
        add(o.input)
    }

    /** Splits extra arguments like a shell would for simple quoting (`'a b'`, `"a b"`). */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (ch in s) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> cur.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; any = true }
                ch.isWhitespace() -> { if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false } }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    /** Finds a client tool on PATH or in the usual install locations. */
    fun locate(name: String, path: String? = System.getenv("PATH")): String? {
        val dirs = path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() } + listOf(
            "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin",
            "/Applications/Postgres.app/Contents/Versions/latest/bin",
        ) + (File("/opt/homebrew/opt").listFiles { f -> f.name.startsWith("postgresql") || f.name.startsWith("libpq") }?.map { "$it/bin" } ?: emptyList()) +
            (File("/usr/lib/postgresql").listFiles()?.sortedDescending()?.map { "$it/bin" } ?: emptyList())
        return dirs.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path
    }
}

/** mysqldump options: a schema, or some of its tables. */
data class MysqlDumpOptions(
    val tool: String,
    val target: PgTarget,
    val output: String,
    val schema: String,
    val tables: List<String> = emptyList(),
    val noData: Boolean = false,
    val noCreateInfo: Boolean = false,
    val addDropTable: Boolean = true,
    val extraArgs: String = "",
)

/** mysqldump / mysql command lines; the password goes in `MYSQL_PWD`, never on the command line. */
object MysqlTools {
    private fun connection(t: PgTarget) = buildList {
        add("--host=${t.host}")
        t.port?.let { add("--port=$it") }
        t.user?.takeIf { it.isNotBlank() }?.let { add("--user=$it") }
    }

    fun dump(o: MysqlDumpOptions): List<String> = buildList {
        add(o.tool)
        addAll(connection(o.target))
        if (o.noData) add("--no-data")
        if (o.noCreateInfo) add("--no-create-info")
        add(if (o.addDropTable) "--add-drop-table" else "--skip-add-drop-table")
        add("--result-file=${o.output}")
        addAll(PgTools.splitArgs(o.extraArgs))
        add(o.schema)
        addAll(o.tables)
    }

    /** `mysql` reads the script from standard input (the runner redirects the file). */
    fun restore(tool: String, target: PgTarget, schema: String, extraArgs: String = ""): List<String> = buildList {
        add(tool)
        addAll(connection(target))
        addAll(PgTools.splitArgs(extraArgs))
        add(schema)
    }
}
