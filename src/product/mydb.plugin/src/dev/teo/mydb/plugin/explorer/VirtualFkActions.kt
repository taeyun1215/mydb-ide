package dev.teo.mydb.plugin.explorer

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.teo.mydb.plugin.model.ModelService
import dev.teo.mydb.plugin.model.VirtualObjects
import java.awt.Dimension
import javax.swing.JComponent

/** Explorer → Add Virtual Foreign Key on a table. */
class AddVirtualForeignKeyAction : ExplorerAction() {
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = (e.nodes().singleOrNull() as? ExplorerNode.Table)?.table?.isView == false }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val n = e.nodes().singleOrNull() as? ExplorerNode.Table ?: return
        val d = VirtualForeignKeyDialog(project, n)
        if (d.showAndGet()) VirtualObjects.getInstance(project).add(d.result())
    }
}

/** Explorer → Remove Virtual Foreign Key on a virtual foreign key node. */
class RemoveVirtualForeignKeyAction : ExplorerAction() {
    private fun node(e: AnActionEvent) = (e.nodes().singleOrNull() as? ExplorerNode.ForeignKey)?.takeIf { it.fk.virtual }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = node(e) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val n = node(e) ?: return
        VirtualObjects.getInstance(e.project ?: return).remove(n.config.id, n.table.name, n.fk.name)
    }
}

class VirtualForeignKeyDialog(project: Project, private val n: ExplorerNode.Table) : DialogWrapper(project, true) {
    private val schema = ModelService.getInstance(project).model(n.config.id)?.schema(n.database, n.schema)
    val columns = CheckBoxList<String>().apply { n.table.columns.forEach { addItem(it.name, it.name, false) } }
    val target = ComboBox(schema?.tables?.filter { !it.isView }?.map { it.name }?.toTypedArray() ?: emptyArray()).apply {
        addActionListener { targetColumns.text = schema?.table(selectedItem as String)?.primaryKey?.columns?.joinToString(", ").orEmpty() }
    }
    val targetColumns = JBTextField()

    init {
        title = "Add Virtual Foreign Key"
        target.selectedItem?.let { t -> targetColumns.text = schema?.table(t as String)?.primaryKey?.columns?.joinToString(", ").orEmpty() }
        init()
    }

    private fun chosen() = (0 until columns.itemsCount).mapNotNull { i -> columns.getItemAt(i)?.takeIf { columns.isItemSelected(i) } }

    fun result() = VirtualObjects.VirtualFk().apply {
        dataSourceId = n.config.id; database = n.database; schema = n.schema; table = n.table.name
        columns = chosen().joinToString(","); refTable = target.selectedItem as String
        refColumns = targetColumns.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
    }

    override fun doValidate() = when {
        chosen().isEmpty() -> com.intellij.openapi.ui.ValidationInfo("Choose at least one column", columns)
        target.selectedItem == null -> com.intellij.openapi.ui.ValidationInfo("Choose the target table", target)
        else -> null
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Columns of ${n.table.name}:") {}
        row { cell(JBScrollPane(columns).apply { preferredSize = Dimension(JBUI.scale(300), JBUI.scale(140)) }).align(AlignX.FILL) }
        row("References:") { cell(target).align(AlignX.FILL) }
        row("Target columns:") { cell(targetColumns).align(AlignX.FILL) }
        row { comment("A virtual foreign key exists only in the IDE: it helps completion, navigation and diagrams.") }
    }
}
