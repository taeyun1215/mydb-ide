package dev.teo.mydb.core.datasource

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object JdbcUrls {
    data class Parts(val host: String, val port: Int?, val database: String?)

    fun build(dbms: Dbms, host: String, port: Int, database: String?): String = when (dbms) {
        Dbms.POSTGRES -> "jdbc:postgresql://$host:$port/${database?.let(::encode).orEmpty()}"
        Dbms.MYSQL -> "jdbc:mysql://$host:$port/${database?.let(::encode).orEmpty()}"
        Dbms.SQLITE -> "jdbc:sqlite:${database.orEmpty()}"
        Dbms.DDL -> "ddl:${database.orEmpty()}"
    }

    fun parse(dbms: Dbms, url: String): Parts? = when (dbms) {
        Dbms.POSTGRES -> parseHostPortDb(url, "jdbc:postgresql://")
        Dbms.MYSQL -> parseHostPortDb(url, "jdbc:mysql://")
        Dbms.SQLITE -> url.removePrefix("jdbc:sqlite:").takeIf { url.startsWith("jdbc:sqlite:") }?.let { Parts("", null, it) }
        Dbms.DDL -> url.removePrefix("ddl:").takeIf { url.startsWith("ddl:") }?.let { Parts("", null, it) }
    }

    private fun parseHostPortDb(url: String, prefix: String): Parts? {
        if (!url.startsWith(prefix)) return null
        val rest = url.removePrefix(prefix).substringBefore('?')
        val authority = rest.substringBefore('/')
        val db = rest.substringAfter('/', "").takeIf { it.isNotEmpty() }?.let(::decode)
        val host: String
        val port: Int?
        if (authority.startsWith("[")) { // IPv6 literal
            host = authority.substringBefore(']') + "]"
            port = authority.substringAfter("]:", "").toIntOrNull()
        } else {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':', "").toIntOrNull()
        }
        return Parts(host, port, db)
    }

    private fun encode(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
    private fun decode(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)
}
