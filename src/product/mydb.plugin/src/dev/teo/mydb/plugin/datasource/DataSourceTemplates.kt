package dev.teo.mydb.plugin.datasource

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.teo.mydb.core.datasource.DataSourceConfig
import java.util.UUID

/**
 * Data source templates: IDE-wide copies of data source settings without secrets
 * introspection scope. A new data source made from a template gets a new id.
 */
@Service(Service.Level.APP)
@State(name = "MyDbDataSourceTemplates", storages = [Storage("mydbDataSourceTemplates.xml")])
class DataSourceTemplates : PersistentStateComponent<DataSourceTemplates.TemplatesState> {
    class TemplatesState {
        @XCollection(style = XCollection.Style.v2)
        var templates: MutableList<Template> = mutableListOf()
    }

    @Tag("template")
    class Template {
        var user: String? = null
        var settings: DataSourceStorage.SharedDataSource = DataSourceStorage.SharedDataSource()
    }

    private var state = TemplatesState()
    override fun getState() = state
    override fun loadState(s: TemplatesState) { state = s }

    fun names(): List<String> = state.templates.map { it.settings.name }

    /** Saves [config] as the template [name], replacing a template of the same name. */
    fun save(name: String, config: DataSourceConfig) {
        state.templates.removeIf { it.settings.name == name }
        state.templates += Template().apply {
            user = config.user
            DataSourceStorage.fillShared(settings, config.copy(name = name))
            settings.id = ""
        }
    }

    /** A new, unsaved data source with the template's settings. */
    fun create(name: String): DataSourceConfig? = state.templates.firstOrNull { it.settings.name == name }?.let { t ->
        DataSourceStorage.fromShared(t.settings, t.user).copy(id = UUID.randomUUID().toString())
    }

    fun remove(name: String) { state.templates.removeIf { it.settings.name == name } }

    companion object { fun getInstance(): DataSourceTemplates = service() }
}
