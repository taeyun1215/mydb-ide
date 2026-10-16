package dev.teo.mydb.core.exec

import dev.teo.mydb.core.datasource.Dbms
import dev.teo.mydb.core.ddl.PgDdlGenerator

/** Identifier quoting of the supported DBMS: double quotes (PostgreSQL, SQLite) or backticks. */
enum class SqlDialect {
    POSTGRES, SQLITE, MYSQL;

    fun quote(identifier: String): String = when (this) {
        MYSQL -> if (PLAIN.matches(identifier) && identifier.lowercase() !in MYSQL_RESERVED) identifier else "`" + identifier.replace("`", "``") + "`"
        else -> PgDdlGenerator.quote(identifier)
    }

    fun qualified(schema: String, name: String) = "${quote(schema)}.${quote(name)}"

    companion object {
        private val PLAIN = Regex("[a-z_][a-z0-9_$]*")
        private val MYSQL_RESERVED = setOf("select", "from", "where", "order", "group", "by", "table", "key", "index", "primary", "foreign",
            "references", "constraint", "default", "check", "column", "insert", "update", "delete", "values", "into", "join", "on",
            "and", "or", "not", "null", "like", "in", "is", "as", "desc", "asc", "limit", "union", "all", "distinct", "having",
            "create", "drop", "alter", "rename", "unique", "range", "interval", "condition", "status", "read", "write", "show")

        fun of(dbms: Dbms) = when (dbms) {
            Dbms.POSTGRES, Dbms.DDL -> POSTGRES
            Dbms.SQLITE -> SQLITE
            Dbms.MYSQL -> MYSQL
        }

        /** From the connection's product name (`MySQL`, `MariaDB`, `SQLite`, `PostgreSQL`). */
        fun of(connection: java.sql.Connection): SqlDialect = runCatching { connection.metaData.databaseProductName }.getOrNull().let { p ->
            when {
                p == null -> POSTGRES
                p.contains("MySQL", true) || p.contains("MariaDB", true) -> MYSQL
                p.contains("SQLite", true) -> SQLITE
                else -> POSTGRES
            }
        }
    }
}
