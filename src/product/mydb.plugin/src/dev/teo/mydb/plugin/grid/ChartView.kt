package dev.teo.mydb.plugin.grid

import com.intellij.database.datagrid.GridUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.teo.mydb.core.chart.ChartData
import dev.teo.mydb.core.chart.ChartType
import dev.teo.mydb.core.exec.ResultPage
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/** A result page shown as a chart in an editor tab. */
class ChartFile(name: String, val page: ResultPage) : LightVirtualFile(name)

class ChartEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ChartFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ChartEditor(file as ChartFile)
    override fun getEditorTypeId() = "mydb-chart"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Options on the left (type, X column, series), the chart on the right. */
class ChartEditor(private val file: ChartFile) : UserDataHolderBase(), FileEditor {
    private val page = file.page
    private val numeric = ChartData.numericColumns(page)
    val type = ComboBox(ChartType.entries.toTypedArray()).apply { renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it.title } }
    val x = ComboBox(page.columns.indices.toList().toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { page.columns[it].label }
        selectedItem = page.columns.indices.firstOrNull { it !in numeric } ?: 0
    }
    val series = CheckBoxList<Int>().apply { numeric.forEachIndexed { i, c -> addItem(c, page.columns[c].label, i == 0) } }
    val canvas = ChartCanvas()
    private val panel = JPanel(BorderLayout())

    init {
        val options = panel {
            row("Type:") { cell(type) }
            row("X axis:") { cell(x) }
            row("Series:") {}
            row { cell(JBScrollPane(series).apply { preferredSize = Dimension(JBUI.scale(180), JBUI.scale(160)) }) }
        }
        type.addActionListener { refresh() }
        x.addActionListener { refresh() }
        series.setCheckBoxListListener { _, _ -> refresh() }
        panel.add(options, BorderLayout.WEST)
        panel.add(canvas, BorderLayout.CENTER)
        refresh()
    }

    fun chosenSeries() = (0 until series.itemsCount).mapNotNull { i -> series.getItemAt(i)?.takeIf { series.isItemSelected(i) } }

    fun refresh() {
        canvas.data = ChartData.of(page, x.selectedItem as Int, chosenSeries())
        canvas.type = type.selectedItem as ChartType
        canvas.repaint()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = canvas
    override fun getName() = "Chart"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() {}
}

/** Bar, line and pie rendering with category labels and a legend. */
class ChartCanvas : JComponent() {
    var data = ChartData(emptyList(), emptyList())
    var type = ChartType.BAR
    private val palette = listOf(Color(0x4A8FE7), Color(0xE8A33D), Color(0x5FB865), Color(0xE5534B), Color(0x9A6FE0), Color(0x8C8C8C))

    init { preferredSize = Dimension(JBUI.scale(600), JBUI.scale(400)) }

    override fun paintComponent(g0: Graphics) {
        val g = g0 as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = UIUtil.getPanelBackground(); g.fillRect(0, 0, width, height)
        if (data.series.isEmpty() || data.categories.isEmpty()) {
            g.color = UIUtil.getContextHelpForeground(); g.drawString("Choose a numeric series", 20, 30); return
        }
        val left = JBUI.scale(60); val top = JBUI.scale(20); val right = width - JBUI.scale(150); val bottom = height - JBUI.scale(40)
        legend(g, right + JBUI.scale(16), top)
        if (type == ChartType.PIE) { pie(g, left, top, right, bottom); return }
        val lo = minOf(0.0, data.min); val hi = maxOf(data.max, lo + 1e-9)
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo) * (bottom - top)).toInt()
        g.color = JBColor.border(); g.drawLine(left, bottom, right, bottom); g.drawLine(left, top, left, bottom)
        g.color = UIUtil.getContextHelpForeground()
        for (k in 0..4) { val v = lo + (hi - lo) * k / 4; g.drawString(fmt(v), 4, y(v) + 4) }
        val n = data.categories.size
        val slot = (right - left).toDouble() / n
        data.categories.forEachIndexed { i, c ->
            val cx = left + (slot * (i + 0.5)).toInt()
            g.color = UIUtil.getContextHelpForeground()
            val label = if (c.length > 12) c.take(11) + "…" else c
            if (n <= 30 || i % (n / 30 + 1) == 0) g.drawString(label, cx - g.fontMetrics.stringWidth(label) / 2, bottom + JBUI.scale(16))
        }
        data.series.forEachIndexed { s, series ->
            g.color = palette[s % palette.size]
            if (type == ChartType.BAR) {
                val w = (slot * 0.8 / data.series.size).toInt().coerceAtLeast(1)
                series.values.forEachIndexed { i, v ->
                    val x0 = left + (slot * i + slot * 0.1).toInt() + s * w
                    val y0 = y(maxOf(v, 0.0)); val y1 = y(minOf(v, 0.0))
                    g.fillRect(x0, y0, w - 1, (y1 - y0).coerceAtLeast(1))
                }
            } else {
                g.stroke = BasicStroke(JBUI.scale(2).toFloat())
                val pts = series.values.mapIndexed { i, v -> (left + (slot * (i + 0.5)).toInt()) to y(v) }
                pts.zipWithNext { a, b -> g.drawLine(a.first, a.second, b.first, b.second) }
                pts.forEach { (px, py) -> g.fillOval(px - 3, py - 3, 6, 6) }
            }
        }
    }

    private fun pie(g: Graphics2D, left: Int, top: Int, right: Int, bottom: Int) {
        val values = data.series.first().values.map { maxOf(it, 0.0) }
        val total = values.sum().takeIf { it > 0 } ?: return
        val d = minOf(right - left, bottom - top)
        var angle = 90.0
        values.forEachIndexed { i, v ->
            val extent = v / total * 360
            g.color = palette[i % palette.size]
            g.fillArc(left, top, d, d, angle.toInt(), -Math.ceil(extent).toInt())
            angle -= extent
        }
    }

    private fun legend(g: Graphics2D, x: Int, y: Int) {
        val names = if (type == ChartType.PIE) data.categories else data.series.map { it.name }
        names.take(20).forEachIndexed { i, name ->
            g.color = palette[i % palette.size]; g.fillRect(x, y + i * JBUI.scale(18), JBUI.scale(10), JBUI.scale(10))
            g.color = UIUtil.getLabelForeground(); g.drawString(name, x + JBUI.scale(16), y + i * JBUI.scale(18) + JBUI.scale(10))
        }
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else "%.2f".format(v)
}

/** Grid toolbar: the rows of the grid as a chart in a new tab. */
class ShowChartAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    private fun page(e: AnActionEvent) = (GridUtil.getDataGrid(e.dataContext)?.dataHookup as? JdbcGridHookUp)?.currentPage()
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = GridUtil.getDataGrid(e.dataContext)?.dataHookup is JdbcGridHookUp
        e.presentation.isEnabled = page(e)?.let { ChartData.numericColumns(it).isNotEmpty() } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val page = page(e) ?: return
        val title = page.sourceTable?.let { (s, t) -> "$s.$t" } ?: "Result"
        FileEditorManager.getInstance(project).openFile(ChartFile("Chart: $title", page), true)
    }
}
