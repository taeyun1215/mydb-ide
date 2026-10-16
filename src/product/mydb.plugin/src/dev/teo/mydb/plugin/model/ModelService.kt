package dev.teo.mydb.plugin.model

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.messages.Topic
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.introspect.PostgresIntrospector
import dev.teo.mydb.core.introspect.RefreshTarget
import dev.teo.mydb.core.model.DbModel
import dev.teo.mydb.core.model.ModelStore
import dev.teo.mydb.plugin.MyDbBundle
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Introspected models of the project's data sources. Loaded from `.idea/mydb/models` at startup and
 * refreshed in the background.
 */
@Service(Service.Level.PROJECT)
class ModelService(private val project: Project) : Disposable {
    private val models = ConcurrentHashMap<String, DbModel>()
    private val store: ModelStore? = project.guessProjectDir()?.let { runCatching { it.toNioPath() }.getOrNull() }?.let { ModelStore(modelsDir(it)) }

    /** Overridable so tests can supply models without a server. */
    var introspectorFactory: (DataSourceConfig) -> dev.teo.mydb.core.introspect.Introspector = { dev.teo.mydb.plugin.datasource.DbConnector.introspector(project, it.dbms) }

    /** The introspected model as stored, without virtual objects (introspection and DDL work on this). */
    fun rawModel(dataSourceId: String): DbModel? = models[dataSourceId] ?: store?.load(dataSourceId)?.also { models[dataSourceId] = it }

    private val overlays = ConcurrentHashMap<String, Triple<DbModel, Long, DbModel>>()

    /** The model with the virtual objects of the project laid over it. */
    fun model(dataSourceId: String): DbModel? {
        val raw = rawModel(dataSourceId) ?: return null
        val virtual = VirtualObjects.getInstance(project)
        overlays[dataSourceId]?.let { (r, v, merged) -> if (r === raw && v == virtual.version) return merged }
        return virtual.overlay(raw).also { overlays[dataSourceId] = Triple(raw, virtual.version, it) }
    }

    fun allModels(): List<Pair<DataSourceConfig, DbModel?>> = DataSourceStorage.getInstance(project).all().map { it to model(it.id) }

    fun put(model: DbModel) {
        models[model.dataSourceId] = model
        runCatching { store?.save(model) }
        dev.teo.mydb.plugin.util.publishOnEdt(project) {
            project.messageBus.syncPublisher(TOPIC).modelChanged(model.dataSourceId)
            // SQL files resolve against the model: re-resolve and re-highlight them
            com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches()
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    fun remove(dataSourceId: String) { models.remove(dataSourceId); store?.delete(dataSourceId) }

    /** Synchronous refresh (call off the EDT). */
    fun refreshNow(config: DataSourceConfig, target: RefreshTarget = RefreshTarget.All): DbModel {
        val password = DataSourceStorage.getInstance(project).password(config.id)
        val model = introspectorFactory(config).introspect(config, password, rawModel(config.id), target)
        put(model)
        return model
    }

    fun refresh(config: DataSourceConfig, target: RefreshTarget = RefreshTarget.All, onDone: (DbModel) -> Unit = {}) {
        object : Task.Backgroundable(project, MyDbBundle.message("introspection.progress", config.name), true) {
            private var result: DbModel? = null
            override fun run(indicator: ProgressIndicator) { result = refreshNow(config, target) }
            override fun onSuccess() { result?.let(onDone) }
            override fun onThrowable(error: Throwable) {
                NotificationGroupManager.getInstance().getNotificationGroup("MyDB")
                    .createNotification(MyDbBundle.message("introspection.failed", config.name), error.message ?: error.toString(), NotificationType.ERROR)
                    .notify(project)
            }
        }.queue()
    }

    override fun dispose() = models.clear()

    fun interface Listener { fun modelChanged(dataSourceId: String) }

    companion object {
        val TOPIC = Topic.create("MyDB model", Listener::class.java)
        fun getInstance(project: Project): ModelService = project.service()
        fun modelsDir(root: Path) = root.resolve(".idea/mydb/models")
    }
}
