package dev.teo.mydb.core.model

import kotlinx.serialization.Serializable

/**
 * Introspected metadata of one data source. Immutable snapshot; refreshes produce a new instance.
 * Object identity inside a database is the PostgreSQL oid where available.
 */
@Serializable
data class DbModel(
    val dataSourceId: String,
    val serverVersion: String,
    val defaultDatabase: String,
    val databases: List<DbDatabase>,
) {
    fun database(name: String) = databases.firstOrNull { it.name == name }
    fun schema(database: String, schema: String) = database(database)?.schema(schema)
    fun allSchemas(): List<Pair<DbDatabase, DbSchema>> = databases.flatMap { d -> d.schemas.map { d to it } }
}

@Serializable
data class DbDatabase(
    val name: String,
    val isDefault: Boolean,
    /** Every schema name that exists on the server, introspected or not (for the scope popup). */
    val allSchemaNames: List<String>,
    val schemas: List<DbSchema>,
) {
    fun schema(name: String) = schemas.firstOrNull { it.name == name }
}

@Serializable
data class DbSchema(
    val name: String,
    val isDefault: Boolean,
    val owner: String? = null,
    val tables: List<DbTable> = emptyList(),
    val sequences: List<DbSequence> = emptyList(),
    val routines: List<DbRoutine> = emptyList(),
    /** Transaction id observed when this schema was last introspected (for incremental refresh). */
    val stateTxid: Long = 0,
) {
    fun table(name: String) = tables.firstOrNull { it.name == name }
    val relationsByKind: Map<RelationKind, List<DbTable>> get() = tables.groupBy { it.kind }
}

@Serializable
enum class RelationKind { TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE, PARTITIONED_TABLE }

@Serializable
data class DbTable(
    val oid: Long,
    val name: String,
    val kind: RelationKind,
    val columns: List<DbColumn>,
    val primaryKey: DbKey? = null,
    val uniqueKeys: List<DbKey> = emptyList(),
    val foreignKeys: List<DbForeignKey> = emptyList(),
    val indexes: List<DbIndex> = emptyList(),
    val checks: List<DbCheck> = emptyList(),
    val comment: String? = null,
    val owner: String? = null,
    val viewDefinition: String? = null,
    /** Relations a view reads, as `schema.name`. */
    val viewReferences: List<String> = emptyList(),
    /** DDL text kept by the database itself. */
    val sourceDdl: String? = null,
) {
    fun column(name: String) = columns.firstOrNull { it.name == name }
    val isView get() = kind == RelationKind.VIEW || kind == RelationKind.MATERIALIZED_VIEW
}

@Serializable
data class DbColumn(
    val name: String,
    val position: Int,
    /** Type as formatted by the server, e.g. `varchar(16)`, `timestamp with time zone`. */
    val type: String,
    val notNull: Boolean,
    val default: String? = null,
    val identity: IdentityKind? = null,
    val comment: String? = null,
    /** Stored generated column: [default] holds the generation expression (`generated always as (…) stored`). */
    val generated: Boolean = false,
) {
    /** `serial`-like: integer column fed by a sequence (`nextval(...)` default). */
    val isSerial get() = default?.startsWith("nextval(") == true
}

@Serializable
enum class IdentityKind { ALWAYS, BY_DEFAULT }

@Serializable
data class DbKey(val name: String, val columns: List<String>, val primary: Boolean, val deferrable: Boolean = false)

@Serializable
data class DbForeignKey(
    val name: String,
    val columns: List<String>,
    val refSchema: String,
    val refTable: String,
    val refColumns: List<String>,
    val onDelete: FkAction = FkAction.NO_ACTION,
    val onUpdate: FkAction = FkAction.NO_ACTION,
    /** Defined in the IDE only: used for navigation, completion and diagrams, never in DDL. */
    val virtual: Boolean = false,
)

@Serializable
enum class FkAction(val sql: String) {
    NO_ACTION("no action"), RESTRICT("restrict"), CASCADE("cascade"), SET_NULL("set null"), SET_DEFAULT("set default")
}

@Serializable
data class DbIndex(
    val name: String,
    /** Column names or expressions, in index order. */
    val columns: List<String>,
    val unique: Boolean,
    val method: String = "btree",
    /** True for indexes that only back a PK/UK constraint; they are not emitted as separate DDL. */
    val backsConstraint: Boolean = false,
    val predicate: String? = null,
)

@Serializable
data class DbCheck(val name: String, val expression: String)

@Serializable
data class DbSequence(val name: String, val ownedByTable: String? = null, val ownedByColumn: String? = null)

@Serializable
data class DbRoutine(val name: String, val kind: RoutineKind, val arguments: String, val returnType: String?, val source: String?)

@Serializable
enum class RoutineKind { FUNCTION, PROCEDURE, AGGREGATE }
