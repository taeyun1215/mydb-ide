package dev.teo.mydb.plugin.datasource

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.teo.mydb.core.datasource.DataSourceConfig
import dev.teo.mydb.plugin.explorer.ExplorerDataKeys
import dev.teo.mydb.plugin.explorer.ExplorerNode
import org.jdom.Element
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.UUID

/**
 * Data sources as XML for the clipboard: the shared settings and the user name, never
 * a password or other secret. Pasting creates new data sources with fresh ids.
 */
object DataSourceTransfer {
    private const val ROOT = "mydb-data-sources"

    fun toXml(configs: List<DataSourceConfig>): String {
        val root = Element(ROOT)
        for (c in configs) {
            val s = DataSourceStorage.SharedDataSource().also { it.id = c.id; DataSourceStorage.fillShared(it, c) }
            root.addContent(XmlSerializer.serialize(s).setAttribute("user", c.user.orEmpty()))
        }
        return JDOMUtil.write(root)
    }

    /** Data sources in [xml], or null when it is not a data source list. */
    fun fromXml(xml: String): List<DataSourceConfig>? = runCatching {
        val root = JDOMUtil.load(xml)
        if (root.name != ROOT) return null
        root.children.map { e ->
            val s = XmlSerializer.deserialize(e, DataSourceStorage.SharedDataSource::class.java)
            DataSourceStorage.fromShared(s, e.getAttributeValue("user")?.ifEmpty { null })
        }
    }.getOrNull()

    /** Adds copies of [configs] with new ids; names get a suffix when already taken. */
    fun paste(project: Project, configs: List<DataSourceConfig>): List<DataSourceConfig> {
        val storage = DataSourceStorage.getInstance(project)
        val names = storage.all().map { it.name }.toMutableSet()
        return configs.map { c ->
            var name = c.name
            var i = 1
            while (name in names) name = "${c.name} (${i++})"
            names += name
            c.copy(id = UUID.randomUUID().toString(), name = name).also { storage.save(it) }
        }
    }
}

class CopyDataSourceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun configs(e: AnActionEvent) = e.getData(ExplorerDataKeys.SELECTED).orEmpty().filterIsInstance<ExplorerNode.DataSource>().map { it.config }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = configs(e).isNotEmpty() }
    override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(DataSourceTransfer.toXml(configs(e))))
}

class PasteDataSourceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    private fun clipboard() = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)?.let(DataSourceTransfer::fromXml)
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.getData(ExplorerDataKeys.SELECTED) != null && clipboard() != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pasted = DataSourceTransfer.paste(project, clipboard() ?: return)
        pasted.forEach { dev.teo.mydb.plugin.model.ModelService.getInstance(project).refresh(it) }
    }
}
