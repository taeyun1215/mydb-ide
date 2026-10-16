package dev.teo.mydb.core.exec

/** Query of the table editor: WHERE and ORDER BY come from the filter bar. */
object TableQuery {
    fun select(schema: String, table: String, where: String? = null, orderBy: String? = null, pageSize: Int = 500, offset: Int = 0,
               dialect: SqlDialect = SqlDialect.POSTGRES): String =
        buildString {
            append("SELECT t.*\nFROM ${dialect.qualified(schema, table)} t")
            if (!where.isNullOrBlank()) append("\nWHERE ").append(where.trim())
            if (!orderBy.isNullOrBlank()) append("\nORDER BY ").append(orderBy.trim())
            append("\nLIMIT ").append(pageSize + 1)
            if (offset > 0) append(" OFFSET ").append(offset)
        }

    fun count(schema: String, table: String, where: String? = null, dialect: SqlDialect = SqlDialect.POSTGRES): String =
        "SELECT count(*)\nFROM ${dialect.qualified(schema, table)} t" + (if (!where.isNullOrBlank()) "\nWHERE ${where.trim()}" else "")
}
