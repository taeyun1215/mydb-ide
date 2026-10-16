package dev.teo.mydb.plugin.model

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.teo.mydb.core.model.DbForeignKey
import dev.teo.mydb.core.model.DbModel

/**
 * Virtual foreign keys: relations the database does not declare, kept in
 * `.idea/mydbVirtualObjects.xml` and laid over the introspected model, so join completion, navigation and
 * diagrams use them. They never appear in generated DDL.
 */
@Service(Service.Level.PROJECT)
@State(name = "MyDbVirtualObjects", storages = [Storage("mydbVirtualObjects.xml")])
class VirtualObjects(private val project: Project) : PersistentStateComponent<VirtualObjects.State> {
    class State {
        @XCollection(style = XCollection.Style.v2)
        var foreignKeys: MutableList<VirtualFk> = mutableListOf()
    }

    @Tag("virtual-foreign-key")
    class VirtualFk {
        var dataSourceId: String = ""
        var database: String = ""
        var schema: String = ""
        var table: String = ""
        var columns: String = ""
        var refTable: String = ""
        var refColumns: String = ""

        val name get() = "${table}_${refTable}_${columns.replace(',', '_')}_vfk"
        fun toDb(): DbForeignKey = DbForeignKey(name, columns.split(',').map { it.trim() }, schema, refTable,
            refColumns.split(',').map { it.trim() }.filter { it.isNotEmpty() }, virtual = true)
    }

    private var state = State()
    @Volatile var version = 0L
        private set

    override fun getState() = state
    override fun loadState(s: State) { state = s; version++ }

    fun all(dataSourceId: String): List<VirtualFk> = state.foreignKeys.filter { it.dataSourceId == dataSourceId }

    fun add(fk: VirtualFk) { state.foreignKeys += fk; changed(fk.dataSourceId) }

    fun remove(dataSourceId: String, table: String, name: String) {
        state.foreignKeys.removeIf { it.dataSourceId == dataSourceId && it.table == table && it.name == name }
        changed(dataSourceId)
    }

    private fun changed(dataSourceId: String) {
        version++
        dev.teo.mydb.plugin.util.publishOnEdt(project) {
            project.messageBus.syncPublisher(ModelService.TOPIC).modelChanged(dataSourceId)
            com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches()
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    /** [model] with the virtual foreign keys of its data source added to their tables. */
    fun overlay(model: DbModel): DbModel {
        val fks = all(model.dataSourceId)
        if (fks.isEmpty()) return model
        return model.copy(databases = model.databases.map { db ->
            db.copy(schemas = db.schemas.map { s ->
                val mine = fks.filter { it.database == db.name && it.schema == s.name }.groupBy { it.table }
                if (mine.isEmpty()) s else s.copy(tables = s.tables.map { t ->
                    mine[t.name]?.let { list -> t.copy(foreignKeys = t.foreignKeys + list.map { it.toDb() }) } ?: t
                })
            })
        })
    }

    companion object { fun getInstance(project: Project): VirtualObjects = project.service() }
}
