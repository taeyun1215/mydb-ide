package dev.teo.mydb.plugin.explorer

import com.intellij.openapi.actionSystem.DataKey
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.model.*

/** Tree node payloads of the database explorer. */
sealed interface ExplorerNode {
    val config: DataSourceConfig
    val label: String

    data class DataSource(override val config: DataSourceConfig, val shown: Int, val total: Int, val connected: Boolean) : ExplorerNode {
        override val label get() = config.name
    }
    data class Database(override val config: DataSourceConfig, val db: DbDatabase, val shown: Int, val total: Int) : ExplorerNode {
        override val label get() = db.name
    }
    data class Schema(override val config: DataSourceConfig, val database: String, val schema: DbSchema) : ExplorerNode {
        override val label get() = schema.name
    }
    data class Folder(override val config: DataSourceConfig, val database: String, val schema: String, val kind: FolderKind, val count: Int, val table: String? = null) : ExplorerNode {
        override val label get() = kind.title
    }
    data class Table(override val config: DataSourceConfig, val database: String, val schema: String, val table: DbTable) : ExplorerNode {
        override val label get() = table.name
    }
    data class Column(override val config: DataSourceConfig, val database: String, val schema: String, val table: DbTable, val column: DbColumn) : ExplorerNode {
        override val label get() = column.name
        val detail: String get() = column.type + (column.default?.let { " = $it" } ?: "")
    }
    data class Key(override val config: DataSourceConfig, val table: DbTable, val key: DbKey) : ExplorerNode {
        override val label get() = key.name
    }
    data class ForeignKey(override val config: DataSourceConfig, val table: DbTable, val fk: DbForeignKey) : ExplorerNode {
        override val label get() = fk.name
    }
    data class Index(override val config: DataSourceConfig, val table: DbTable, val index: DbIndex) : ExplorerNode {
        override val label get() = index.name
    }
    data class Sequence(override val config: DataSourceConfig, val schema: String, val sequence: DbSequence) : ExplorerNode {
        override val label get() = sequence.name
    }
    data class Routine(override val config: DataSourceConfig, val schema: String, val routine: DbRoutine) : ExplorerNode {
        override val label get() = "${routine.name}(${routine.arguments})"
    }
}

enum class FolderKind(val title: String) {
    TABLES("tables"), VIEWS("views"), MATERIALIZED_VIEWS("materialized views"), SEQUENCES("sequences"), ROUTINES("routines"),
    COLUMNS("columns"), KEYS("keys"), FOREIGN_KEYS("foreign keys"), INDEXES("indexes"), CHECKS("checks"),
}

object ExplorerDataKeys {
    val SELECTED: DataKey<List<ExplorerNode>> = DataKey.create("mydb.explorer.selected")
    val PANEL: DataKey<DatabaseExplorerPanel> = DataKey.create("mydb.explorer.panel")
}
