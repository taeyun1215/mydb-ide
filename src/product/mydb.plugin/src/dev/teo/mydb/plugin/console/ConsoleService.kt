package dev.teo.mydb.plugin.console

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import dev.teo.mydb.core.connection.Connector
import dev.teo.mydb.core.connection.SessionPurpose
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.SessionListener
import dev.teo.mydb.core.history.QueryHistory
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** A live JDBC session owned by a console or a table editor. */
class SessionHandle(val key: String, val title: String, val dataSource: DataSourceConfig, val session: Session) {
    @Volatile var lastMillis: Long? = null
}

/** Transaction mode and isolation chosen in a console's Tx dropdown. */
data class TxSettings(val mode: dev.teo.mydb.core.datasource.TxMode, val isolation: dev.teo.mydb.core.datasource.IsolationLevel)

/**
 * Query consoles: one file per data source under the IDE config directory, one dedicated
 * session per console or table editor, statement history per data source.
 */
@Service(Service.Level.PROJECT)
class ConsoleService(private val project: Project) : Disposable {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()
    private val txSettings = ConcurrentHashMap<String, TxSettings>()
    val history = QueryHistory(PathManager.getConfigDir().resolve("mydb/consoles/.history"))

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = forget(file.url)
        })
    }

    fun consoleDir(dataSourceId: String): Path = PathManager.getConfigDir().resolve("mydb/consoles/db/$dataSourceId")

    /** Opens (creating if needed) the default console of a data source. */
    fun openConsole(config: DataSourceConfig, newConsole: Boolean = false): VirtualFile {
        val dir = consoleDir(config.id)
        Files.createDirectories(dir)
        val name = if (newConsole) nextName(dir) else "console.sql"
        val path = dir.resolve(name)
        if (!Files.exists(path)) Files.writeString(path, "")
        val vf = WriteAction.computeAndWait<VirtualFile, RuntimeException> { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!! }
        SqlDataSourceBinding.getInstance(project).bind(vf, config.id)
        FileEditorManager.getInstance(project).openFile(vf, true)
        return vf
    }

    private fun nextName(dir: Path): String {
        var i = 1
        while (Files.exists(dir.resolve("console_$i.sql"))) i++
        return "console_$i.sql"
    }

    /** Tx settings of a console: chosen ones, else the data source default. */
    fun txSettings(key: String, config: DataSourceConfig): TxSettings =
        txSettings[key] ?: TxSettings(config.txMode, dev.teo.mydb.core.datasource.IsolationLevel.DATABASE_DEFAULT)

    /**
     * Changes a console's Tx settings; a live session is switched through JDBC only, without sending
     * statements. Blocking when a session is open: call off the EDT.
     */
    fun setTxSettings(key: String, config: DataSourceConfig, settings: TxSettings) {
        txSettings[key] = settings
        sessions[key]?.let { h ->
            runCatching {
                h.session.txMode = settings.mode
                if (h.session.isolation != settings.isolation) h.session.isolation = settings.isolation
            }.onFailure { OutputLog.getInstance(project).error(h.title, it.message ?: it.toString()) }
        }
        publish()
    }

    /** Session for a console file or table editor key; connects lazily (call off the EDT). */
    fun session(key: String, title: String, config: DataSourceConfig, purpose: SessionPurpose = SessionPurpose.CONSOLE): SessionHandle =
        sessions[key]?.takeIf { !it.session.connection.isClosed } ?: run {
            val password = DataSourceStorage.getInstance(project).password(config.id)
            val tx = txSettings(key, config)
            val s = Session(dev.teo.mydb.plugin.datasource.DbConnector.get(project).open(config, password, purpose), tx.mode)
            s.addListener(object : SessionListener {
                override fun transactionStateChanged(open: Boolean) = publish()
                override fun message(text: String) = OutputLog.getInstance(project).info(config, title, text)
            })
            if (tx.isolation != dev.teo.mydb.core.datasource.IsolationLevel.DATABASE_DEFAULT) s.isolation = tx.isolation
            s.pageSize = dev.teo.mydb.plugin.settings.MyDbSettings.get().pageSize
            SessionHandle(key, title, config, s).also { sessions[key] = it; publish() }
        }

    fun existing(key: String): SessionHandle? = sessions[key]
    fun all(): List<SessionHandle> = sessions.values.sortedBy { it.title }

    fun close(key: String) {
        sessions.remove(key)?.let {
            runCatching { it.session.close() }
            if (sessions.values.none { other -> other.title == it.title }) OutputLog.getInstance(project).release(it.title)
        }
        publish()
    }

    /** Closes the session of a closed console and drops its Tx settings. */
    fun forget(key: String) {
        txSettings.remove(key)
        close(key)
    }

    fun publish() = dev.teo.mydb.plugin.util.publishOnEdt(project) { project.messageBus.syncPublisher(TOPIC).sessionsChanged() }

    override fun dispose() { sessions.values.forEach { runCatching { it.session.close() } }; sessions.clear() }

    fun interface Listener { fun sessionsChanged() }

    companion object {
        val TOPIC = Topic.create("MyDB sessions", Listener::class.java)
        fun getInstance(project: Project): ConsoleService = project.service()
    }
}

/**
 * Console tabs read `console [<data source>]`; the window title follows the tab title, so it
 * names the data source instead of the console file path.
 */
class ConsoleTabTitleProvider : com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider, com.intellij.openapi.project.DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (!file.path.contains("/mydb/consoles/db/")) return null
        val id = SqlDataSourceBinding.getInstance(project).dataSourceId(file) ?: return null
        val ds = DataSourceStorage.getInstance(project).find(id) ?: return null
        return "${file.nameWithoutExtension} [${ds.name}]"
    }
}
