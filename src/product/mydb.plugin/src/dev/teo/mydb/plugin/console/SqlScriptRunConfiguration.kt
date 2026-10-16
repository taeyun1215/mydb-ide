package dev.teo.mydb.plugin.console

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.StoredProperty
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.teo.mydb.core.exec.Session
import dev.teo.mydb.core.exec.StatementResult
import dev.teo.mydb.core.exec.StatementSplitter
import dev.teo.mydb.plugin.datasource.DataSourceStorage
import dev.teo.mydb.plugin.datasource.DbConnector
import dev.teo.mydb.plugin.sql.SqlDataSourceBinding
import dev.teo.mydb.plugin.sql.SqlFileType
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/** Run/Debug configuration type "SQL Script". */
class SqlScriptConfigurationType : ConfigurationTypeBase(ID, "SQL Script", "Run a SQL file against a data source", AllIcons.Nodes.DataTables) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId() = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration = SqlScriptRunConfiguration(project, this, "SQL Script")
            override fun getOptionsClass() = SqlScriptOptions::class.java
        })
    }
    companion object {
        const val ID = "MyDbSqlScript"
        fun getInstance(): SqlScriptConfigurationType = ConfigurationType.CONFIGURATION_TYPE_EP.findExtensionOrFail(SqlScriptConfigurationType::class.java)
    }
}

class SqlScriptOptions : RunConfigurationOptions() {
    private val dataSourceProp: StoredProperty<String?> = string("").provideDelegate(this, "dataSourceId")
    private val scriptProp: StoredProperty<String?> = string("").provideDelegate(this, "scriptPath")
    private val schemaProp: StoredProperty<String?> = string("").provideDelegate(this, "schema")
    var dataSourceId: String get() = dataSourceProp.getValue(this).orEmpty(); set(v) { dataSourceProp.setValue(this, v) }
    var scriptPath: String get() = scriptProp.getValue(this).orEmpty(); set(v) { scriptProp.setValue(this, v) }
    var schema: String get() = schemaProp.getValue(this).orEmpty(); set(v) { schemaProp.setValue(this, v) }
}

class SqlScriptRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<SqlScriptOptions>(project, factory, name) {
    public override fun getOptions() = super.getOptions() as SqlScriptOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SqlScriptSettingsEditor(project)

    override fun checkConfiguration() {
        if (DataSourceStorage.getInstance(project).find(options.dataSourceId) == null)
            throw com.intellij.execution.configurations.RuntimeConfigurationError("Choose a data source")
        if (options.scriptPath.isBlank() || !Files.isRegularFile(Path.of(options.scriptPath)))
            throw com.intellij.execution.configurations.RuntimeConfigurationError("Choose an existing SQL file")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = RunProfileState { _, _ -> execute() }

    /** Starts the script and returns its console and process (tests read the transcript). */
    fun execute(): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val handler = SqlScriptProcess(project, options.dataSourceId, Path.of(options.scriptPath), options.schema.ifBlank { null })
        console.attachToProcess(handler)
        handler.start()
        return DefaultExecutionResult(console, handler)
    }
}

/**
 * Runs the statements of a script one by one on a session of its own: each statement and
 * its outcome go to the output; the first error stops the run with exit code 1.
 */
class SqlScriptProcess(private val project: Project, private val dataSourceId: String, private val script: Path, private val schema: String?) : ProcessHandler() {
    val transcript = StringBuilder()
    @Volatile var finishedWith: Int? = null
        private set

    private fun out(text: String, error: Boolean = false) {
        transcript.append(text)
        notifyTextAvailable(text, if (error) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
    }

    fun start() {
        startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            var code = 0
            try {
                val config = DataSourceStorage.getInstance(project).find(dataSourceId) ?: error("data source not found")
                val password = DataSourceStorage.getInstance(project).password(config.id)
                Session(DbConnector.get(project).open(config, password)).use { s ->
                    if (schema != null && config.dbms == dev.teo.mydb.core.datasource.Dbms.POSTGRES)
                        s.execute("set search_path to " + dev.teo.mydb.core.exec.SqlDialect.POSTGRES.quote(schema))
                    if (schema != null && config.dbms == dev.teo.mydb.core.datasource.Dbms.MYSQL) s.execute("use " + dev.teo.mydb.core.exec.SqlDialect.MYSQL.quote(schema))
                    for (st in StatementSplitter.split(Files.readString(script))) {
                        if (isProcessTerminating || isProcessTerminated) { code = 1; break }
                        out("> ${st.text.replace(Regex("\\s+"), " ")}\n")
                        when (val r = s.execute(st.text, 1000)) {
                            is StatementResult.Rows -> out("  ${r.page.rows.size}${if (r.page.hasMore) "+" else ""} row(s) in ${r.page.executionMillis} ms\n")
                            is StatementResult.Update -> out("  ${r.count} row(s) affected in ${r.millis} ms\n")
                            is StatementResult.Error -> { out("  [${r.sqlState ?: ""}] ${r.message}\n", error = true); code = 1; break }
                        }
                    }
                }
            } catch (e: Exception) {
                out("${e.message ?: e}\n", error = true); code = 1
            }
            finishedWith = code
            notifyProcessTerminated(code)
        }
    }

    override fun destroyProcessImpl() { if (finishedWith == null) { finishedWith = 1; notifyProcessTerminated(1) } }
    override fun detachProcessImpl() = notifyProcessDetached()
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
}

class SqlScriptSettingsEditor(private val project: Project) : SettingsEditor<SqlScriptRunConfiguration>() {
    private val dataSources = ComboBox(DataSourceStorage.getInstance(project).all().toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.name }
    }
    private val script = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withExtensionFilter("SQL", "sql").withTitle("SQL Script"))
    }
    private val schema = JBTextField()

    override fun resetEditorFrom(c: SqlScriptRunConfiguration) {
        dataSources.selectedItem = DataSourceStorage.getInstance(project).find(c.options.dataSourceId)
        script.text = c.options.scriptPath
        schema.text = c.options.schema
    }

    override fun applyEditorTo(c: SqlScriptRunConfiguration) {
        c.options.dataSourceId = (dataSources.selectedItem as? dev.teo.mydb.core.datasource.DataSourceConfig)?.id.orEmpty()
        c.options.scriptPath = script.text.trim()
        c.options.schema = schema.text.trim()
    }

    override fun createEditor(): JComponent = panel {
        row("Data source:") { cell(dataSources).align(AlignX.FILL) }
        row("Script:") { cell(script).align(AlignX.FILL) }
        row("Schema:") { cell(schema).align(AlignX.FILL) }
    }
}

/** "Run 'file.sql'" from a SQL file attached to a data source. */
class SqlScriptRunConfigurationProducer : LazyRunConfigurationProducer<SqlScriptRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = SqlScriptConfigurationType.getInstance().configurationFactories.single()

    override fun setupConfigurationFromContext(configuration: SqlScriptRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val file = context.location?.virtualFile?.takeIf { it.fileType == SqlFileType && it.isInLocalFileSystem } ?: return false
        val ctx = SqlDataSourceBinding.getInstance(context.project).context(file) ?: return false
        configuration.options.dataSourceId = ctx.config.id
        configuration.options.scriptPath = file.path
        configuration.options.schema = ctx.schema.orEmpty()
        configuration.name = file.name
        return true
    }

    override fun isConfigurationFromContext(configuration: SqlScriptRunConfiguration, context: ConfigurationContext): Boolean =
        context.location?.virtualFile?.path == configuration.options.scriptPath
}
