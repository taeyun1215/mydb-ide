package dev.teo.mydb.core.ddl

import dev.teo.mydb.core.ddl.PgDdlGenerator.Companion.quote

/** Schema being created in the "New" popup. */
data class SchemaDraft(var name: String = "schema_name", var comment: String? = null, var owner: String? = null,
                       var dialect: dev.teo.mydb.core.exec.SqlDialect = dev.teo.mydb.core.exec.SqlDialect.POSTGRES)

/** View being created: a name and its query. */
data class ViewDraft(var schema: String, var name: String = "view_name", var query: String = "select 1", var comment: String? = null,
                     var materialized: Boolean = false, var orReplace: Boolean = false,
                     var dialect: dev.teo.mydb.core.exec.SqlDialect = dev.teo.mydb.core.exec.SqlDialect.POSTGRES)

/** Sequence being created; unset options keep the server defaults. */
data class SequenceDraft(var schema: String, var name: String = "sequence_name", var type: String? = null, var start: Long? = null,
                         var increment: Long? = null, var min: Long? = null, var max: Long? = null, var cache: Long? = null,
                         var cycle: Boolean = false, var comment: String? = null)

/** `create schema / view / sequence` scripts for the object dialogs. */
object PgObjectDdl {
    private fun literal(s: String) = "'" + s.replace("'", "''") + "'"
    private fun q(schema: String, name: String) = "${quote(schema)}.${quote(name)}"

    fun schema(d: SchemaDraft): List<String> = buildList {
        if (d.dialect != dev.teo.mydb.core.exec.SqlDialect.POSTGRES) { add("create schema ${d.dialect.quote(d.name)};"); return@buildList }
        add("create schema ${quote(d.name)}${d.owner?.takeIf { it.isNotBlank() }?.let { " authorization ${quote(it)}" } ?: ""};")
        d.comment?.takeIf { it.isNotBlank() }?.let { add("comment on schema ${quote(d.name)} is ${literal(it)};") }
    }

    fun view(d: ViewDraft): List<String> = buildList {
        val kw = when {
            d.materialized -> "create materialized view"
            d.orReplace -> "create or replace view"
            else -> "create view"
        }
        if (d.dialect != dev.teo.mydb.core.exec.SqlDialect.POSTGRES) {
            add("${if (d.orReplace && d.dialect == dev.teo.mydb.core.exec.SqlDialect.MYSQL) "create or replace view" else "create view"} ${d.dialect.qualified(d.schema, d.name)} as\n${d.query.trim().removeSuffix(";").trimEnd()};")
            return@buildList
        }
        add("$kw ${q(d.schema, d.name)} as\n${d.query.trim().removeSuffix(";").trimEnd()};")
        d.comment?.takeIf { it.isNotBlank() }?.let {
            add("comment on ${if (d.materialized) "materialized view" else "view"} ${q(d.schema, d.name)} is ${literal(it)};")
        }
    }

    fun sequence(d: SequenceDraft): List<String> = buildList {
        val opts = buildList {
            d.type?.takeIf { it.isNotBlank() }?.let { add("as $it") }
            d.increment?.let { add("increment by $it") }
            d.min?.let { add("minvalue $it") }
            d.max?.let { add("maxvalue $it") }
            d.start?.let { add("start with $it") }
            d.cache?.let { add("cache $it") }
            if (d.cycle) add("cycle")
        }
        add("create sequence ${q(d.schema, d.name)}" + opts.joinToString("") { "\n    $it" } + ";")
        d.comment?.takeIf { it.isNotBlank() }?.let { add("comment on sequence ${q(d.schema, d.name)} is ${literal(it)};") }
    }

    /** Problems shown under the preview, like the table dialog. */
    fun problems(name: String, extra: List<String> = emptyList()): List<String> = buildList {
        if (name.isBlank()) add("Name is empty")
        addAll(extra)
    }

    fun sequenceProblems(d: SequenceDraft) = problems(d.name, buildList {
        if (d.increment == 0L) add("Increment must not be zero")
        if (d.min != null && d.max != null && d.min!! > d.max!!) add("Min value is greater than max value")
    })

    fun viewProblems(d: ViewDraft) = problems(d.name, if (d.query.isBlank()) listOf("Query is empty") else emptyList())
}
