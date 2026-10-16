package dev.teo.mydb.plugin.sql

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.xmlb.annotations.MapAnnotation
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.model.DbDatabase
import dev.teo.mydb.core.model.DbModel
import dev.teo.mydb.core.model.DbSchema
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.model.ModelService

/** Data source and current schema a SQL file/console is attached to. */
data class SqlContext(val config: DataSourceConfig, val model: DbModel?, val database: String, val schema: String?) {
    val db: DbDatabase? get() = model?.database(database)
    val currentSchema: DbSchema? get() = schema?.let { db?.schema(it) }
    /** Schemas searched for unqualified names: the current one, then pg_catalog (PostgreSQL search_path). */
    val searchPath: List<DbSchema> get() = listOfNotNull(currentSchema)
}

@Service(Service.Level.PROJECT)
@State(name = "MyDbFileBindings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SqlDataSourceBinding(private val project: Project) : PersistentStateComponent<SqlDataSourceBinding.State> {
    class State {
        @MapAnnotation var dataSources: MutableMap<String, String> = mutableMapOf()
        @MapAnnotation var schemas: MutableMap<String, String> = mutableMapOf()
        /** Resolution scopes: a directory (or file) URL → data source id for SQL files without their own binding. */
        @MapAnnotation var scopes: MutableMap<String, String> = mutableMapOf()
    }

    /** Scopes as (URL, data source id), most specific first. */
    fun scopes(): List<Pair<String, String>> = state.scopes.entries.map { it.key to it.value }.sortedByDescending { it.first.length }

    fun setScopes(entries: List<Pair<String, String>>) {
        state.scopes = entries.toMap().toMutableMap()
    }

    /** Data source of [file]: its own binding, else the most specific resolution scope containing it. */
    fun effectiveDataSourceId(file: VirtualFile): String? = state.dataSources[file.url]
        ?: scopes().firstOrNull { (url, _) -> file.url == url || file.url.startsWith(url.trimEnd('/') + "/") }?.second

    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) { state = s }

    fun bind(file: VirtualFile, dataSourceId: String, schema: String? = null) {
        state.dataSources[file.url] = dataSourceId
        if (schema != null) state.schemas[file.url] = schema else state.schemas.remove(file.url)
        file.putUserData(CONTEXT_STAMP, null)
        com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(file) // console toolbar
    }

    fun setSchema(file: VirtualFile, schema: String) { state.schemas[file.url] = schema }

    fun dataSourceId(file: VirtualFile): String? = state.dataSources[file.url]

    fun context(file: VirtualFile): SqlContext? {
        val id = effectiveDataSourceId(file) ?: return null
        val config = DataSourceStorage.getInstance(project).find(id) ?: return null
        val model = ModelService.getInstance(project).model(id)
        val db = model?.databases?.firstOrNull { it.isDefault } ?: model?.databases?.firstOrNull()
        val schema = state.schemas[file.url] ?: db?.schemas?.firstOrNull { it.isDefault }?.name ?: db?.schemas?.firstOrNull()?.name
        return SqlContext(config, model, db?.name ?: model?.defaultDatabase ?: "", schema)
    }

    companion object {
        private val CONTEXT_STAMP = Key.create<Any>("mydb.sql.context")
        fun getInstance(project: Project): SqlDataSourceBinding = project.service()

        fun context(file: PsiFile): SqlContext? {
            val vf = file.originalFile.virtualFile ?: file.viewProvider.virtualFile
            return getInstance(file.project).context(vf)
        }
    }
}
