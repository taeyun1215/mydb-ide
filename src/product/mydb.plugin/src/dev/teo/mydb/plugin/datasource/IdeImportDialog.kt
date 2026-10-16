package dev.teo.mydb.plugin.datasource

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.datasource.IdeDataSourceImport
import dev.teo.mydb.core.datasource.IdeDataSourceImport.ImportedDataSource
import java.awt.Dimension
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Projects of installed IDEs that have data sources, most recent IDE first. */
object IdeProjects {
    // where other IDEs of the platform keep their settings
    private val base: Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "JetBrains")

    fun ideConfigDirs(): List<Path> = runCatching {
        Files.list(base).use { s -> s.filter { Files.isDirectory(it.resolve("options")) }.toList() }
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it.resolve("options")).toMillis() }.getOrDefault(0) }
    }.getOrDefault(emptyList())

    fun projects(): List<File> = ideConfigDirs().flatMap { dir ->
        val recent = dir.resolve("options/recentProjects.xml").toFile().takeIf { it.isFile }?.readText() ?: return@flatMap emptyList()
        Regex("""key="([^"]+)"""").findAll(recent).map { File(it.groupValues[1].replace("\$USER_HOME\$", System.getProperty("user.home"))) }.toList()
    }.distinct().filter { File(it, ".idea/dataSources.xml").isFile }

    /** SSH configurations of every IDE, the most recently used IDE winning on equal ids. */
    fun sshConfigs(): Map<String, IdeDataSourceImport.SshEntry> =
        ideConfigDirs().reversed().fold(emptyMap()) { acc, dir -> acc + IdeDataSourceImport.sshConfigs(dir.resolve("options/sshConfigs.xml").toFile()) }
}

/** Data source dialog / explorer: import the data sources of another IDE project. */
class ImportIdeDataSourcesAction : DumbAwareAction("Import Data Sources from IDE Project…") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) { IdeImportDialog(e.project ?: return).show() }
}

class IdeImportDialog(private val project: Project) : DialogWrapper(project, true) {
    val projectDir = TextFieldWithBrowseButton().apply {
        text = IdeProjects.projects().firstOrNull()?.path.orEmpty()
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("IDE Project"))
    }
    val list = CheckBoxList<ImportedDataSource>()
    val copyPasswords = JBCheckBox("Copy passwords from the macOS keychain (macOS asks you to allow each one)", true)
    private var found: List<ImportedDataSource> = emptyList()

    init {
        title = "Import Data Sources"
        projectDir.textField.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = reload() })
        init()
        reload()
    }

    fun reload() {
        found = runCatching { IdeDataSourceImport.read(File(projectDir.text.trim()), IdeProjects.sshConfigs()) }.getOrDefault(emptyList())
        list.clear()
        val existing = DataSourceStorage.getInstance(project).all().map { it.name }.toSet()
        for (d in found) {
            val label = d.name + (d.problem?.let { "  ($it)" } ?: "") + (if (d.name in existing) "  (already here)" else "")
            list.addItem(d, label, d.config != null && d.name !in existing)
        }
    }

    fun chosen(): List<ImportedDataSource> = (0 until list.itemsCount).mapNotNull { i -> list.getItemAt(i)?.takeIf { list.isItemSelected(i) && it.config != null } }

    override fun createCenterPanel(): JComponent = panel {
        row("Project:") { cell(projectDir).align(AlignX.FILL) }
        row { comment("IDE projects with data sources on this computer: " + IdeProjects.projects().joinToString(", ") { it.name }.ifEmpty { "none found" }) }
        row { cell(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(260)) }).align(AlignX.FILL) }
        row { cell(copyPasswords) }
        row { comment("Connections, users, SSH tunnels and schema choices are copied. Nothing is changed in the other IDE.") }
    }

    override fun doOKAction() {
        val items = chosen()
        val copy = copyPasswords.isSelected
        super.doOKAction()
        object : Task.Backgroundable(project, "Importing data sources", true) {
            override fun run(indicator: ProgressIndicator) {
                val imported = IdeImport.apply(project, items, copy)
                imported.filter { DataSourceStorage.getInstance(project).password(it.id) != null }
                    .forEach { dev.teo.mydb.plugin.model.ModelService.getInstance(project).refresh(it) }
            }
        }.queue()
    }
}

object IdeImport {
    /** Creates the data sources (and their SSH configurations); returns them. Blocking when copying passwords. */
    fun apply(project: Project, items: List<ImportedDataSource>, copyPasswords: Boolean,
              keychain: (String) -> String? = ::readKeychain): List<dev.teo.mydb.core.datasource.DataSourceConfig> {
        val storage = DataSourceStorage.getInstance(project)
        val ssh = SshConfigurations.getInstance()
        return items.mapNotNull { item ->
            var config = item.config ?: return@mapNotNull null
            item.ssh?.let { s ->
                if (ssh.find(s.id) == null) ssh.replaceAll(ssh.all() + SshConfigurations.Entry().apply {
                    id = s.id; name = s.name; host = s.host; port = s.port; user = s.user; keyFile = s.keyFile
                    auth = (if (s.keyFile != null) dev.teo.mydb.core.datasource.SshAuth.KEY_PAIR else dev.teo.mydb.core.datasource.SshAuth.PASSWORD).name
                })
                config = config.copy(ssh = config.ssh.copy(configId = s.id))
            }
            storage.save(config)
            if (copyPasswords) keychain(item.keychainService)?.let { storage.setPassword(config, it) }
            config
        }
    }

    /** The IDE's stored password: `security` asks the user to allow access to that keychain item. */
    fun readKeychain(service: String): String? = runCatching {
        val p = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-w").redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText().trimEnd('\n')
        if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()
}
