package dev.teo.mydb.plugin.diagram

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.teo.mydb.core.diagram.Diagram
import dev.teo.mydb.core.diagram.DiagramExport
import dev.teo.mydb.core.diagram.Diagrams
import dev.teo.mydb.core.diagram.LayeredLayout
import dev.teo.mydb.core.model.DbSchema
import dev.teo.mydb.core.plan.QueryPlan
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel

/** A diagram tab; [positionKey] identifies saved node positions (null: positions are not kept). */
class DiagramFile(name: String, val diagram: Diagram, val direction: LayeredLayout.Direction, val positionKey: String? = null) : LightVirtualFile(name)

object DiagramEditors {
    /** ERD of a schema, or of one table with its neighbours, laid out or restored from saved positions. */
    fun erdFile(project: Project, dataSourceId: String, database: String, schema: DbSchema, table: String? = null): DiagramFile {
        val d = if (table == null) Diagrams.erd(schema) else Diagrams.neighbourhood(schema, table)
        LayeredLayout.apply(d)
        val key = listOfNotNull(dataSourceId, database, schema.name, table).joinToString("-")
        DiagramPositions.restore(project, key, d)
        return DiagramFile(table ?: schema.name, d, LayeredLayout.Direction.TOP_DOWN, key)
    }

    fun openErd(project: Project, dataSourceId: String, database: String, schema: DbSchema, table: String? = null) {
        FileEditorManager.getInstance(project).openFile(erdFile(project, dataSourceId, database, schema, table), true)
    }

    /** ⌥⌘U: the diagram in a popup instead of an editor tab. */
    fun showPopup(project: Project, file: DiagramFile, dataContext: com.intellij.openapi.actionSystem.DataContext) {
        val canvas = DiagramCanvas(file.diagram)
        val scroll = JBScrollPane(canvas).apply { preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(800), com.intellij.util.ui.JBUI.scale(560)) }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, canvas)
            .setTitle(file.name).setResizable(true).setMovable(true).setRequestFocus(true)
            .createPopup().showInBestPositionFor(dataContext)
    }

    fun openPlan(project: Project, plan: QueryPlan) {
        val d = Diagrams.plan(plan.root)
        LayeredLayout.applyTree(d, "0")
        val title = plan.root.flatten().drop(1).firstOrNull { it.title.contains(" of ") }?.title ?: "Query plan"
        FileEditorManager.getInstance(project).openFile(DiagramFile(title, d, LayeredLayout.Direction.LEFT_RIGHT), true)
    }
}

class DiagramEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is DiagramFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = DiagramEditor(project, file as DiagramFile)
    override fun getEditorTypeId() = "mydb-diagram"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class DiagramEditor(private val project: Project, val file: DiagramFile) : UserDataHolderBase(), FileEditor {
    val canvas = DiagramCanvas(file.diagram) { file.positionKey?.let { DiagramPositions.save(project, it, file.diagram) } }
    private val panel = JPanel(BorderLayout())
    private val scroll = JBScrollPane(canvas)

    /** Rows of the property panel (plan diagrams), for tests. */
    var propertiesModel: javax.swing.table.DefaultTableModel? = null
        private set


    init {
        val g = DefaultActionGroup(
            act("Zoom In", AllIcons.Graph.ZoomIn) { canvas.zoom(1.25) },
            act("Zoom Out", AllIcons.Graph.ZoomOut) { canvas.zoom(0.8) },
            act("Actual Size", AllIcons.Graph.ActualZoom) { canvas.setScale(1.0) },
            act("Fit Content", AllIcons.General.FitContent) { scroll.viewport.extentSize.let { canvas.fit(it.width, it.height) } },
            act("Re-layout", AllIcons.Actions.Refresh) {
                if (file.positionKey == null && file.diagram.nodes.any { it.kind == "plan" }) LayeredLayout.applyTree(file.diagram, "0")
                else LayeredLayout.apply(file.diagram, file.direction)
                canvas.revalidate(); canvas.repaint()
                file.positionKey?.let { DiagramPositions.save(project, it, file.diagram) }
            },
            Separator.getInstance(),
            viewOptionsGroup(),
            Separator.getInstance(),
            act("Copy Diagram as Image", AllIcons.Actions.Copy) { CopyPasteManager.getInstance().setContents(ImageSelection(canvas.image())) },
            exportGroup(),
        )
        // the same actions on right click in the canvas
        com.intellij.ui.PopupHandler.installPopupMenu(canvas, g, "MyDbDiagramPopup")
        val tb = ActionManager.getInstance().createActionToolbar("MyDbDiagram", g, true)
        tb.targetComponent = canvas
        panel.add(tb.component, BorderLayout.NORTH)
        // plan diagrams: properties of the clicked node under the diagram
        if (file.diagram.nodes.any { it.properties.isNotEmpty() }) {
            val props = javax.swing.table.DefaultTableModel(arrayOf("Property", "Value"), 0)
            canvas.selectionListeners += { id ->
                props.rowCount = 0
                id?.let { file.diagram.node(it).properties.forEach { (k, v) -> props.addRow(arrayOf(k, v)) } }
            }
            panel.add(com.intellij.ui.OnePixelSplitter(true, 0.75f).apply {
                firstComponent = scroll
                secondComponent = JBScrollPane(com.intellij.ui.table.JBTable(props))
            }, BorderLayout.CENTER)
            propertiesModel = props
        } else panel.add(scroll, BorderLayout.CENTER)
        // fit once, when the view first gets its size
        scroll.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var done = false
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (done || scroll.viewport.width <= 0) return
                done = true
                canvas.fit(scroll.viewport.width, scroll.viewport.height)
            }
        })
    }

    /** Card contents and view lines; only for ERDs. */
    private fun viewOptionsGroup() = DefaultActionGroup("View Options", true).apply {
        templatePresentation.icon = AllIcons.Actions.Show
        fun option(text: String, selected: (dev.teo.mydb.core.diagram.ErdOptions) -> Boolean,
                   change: (dev.teo.mydb.core.diagram.ErdOptions) -> dev.teo.mydb.core.diagram.ErdOptions) =
            object : com.intellij.openapi.project.DumbAwareToggleAction(text) {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { super.update(e); e.presentation.isEnabledAndVisible = file.positionKey != null }
                override fun isSelected(e: AnActionEvent) = selected(file.diagram.options)
                override fun setSelected(e: AnActionEvent, state: Boolean) { setOptions(change(file.diagram.options)) }
            }
        add(option("All Columns", { it.columns == dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.ALL }) { it.copy(columns = dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.ALL) })
        add(option("Key Columns Only", { it.columns == dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.KEYS }) { it.copy(columns = dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.KEYS) })
        add(option("No Columns", { it.columns == dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.NONE }) { it.copy(columns = dev.teo.mydb.core.diagram.ErdOptions.ColumnMode.NONE) })
        addSeparator()
        add(option("Column Types", { it.types }) { it.copy(types = !it.types) })
        add(option("View Dependencies", { it.viewEdges }) { it.copy(viewEdges = !it.viewEdges) })
    }

    fun setOptions(o: dev.teo.mydb.core.diagram.ErdOptions) {
        file.diagram.apply(o)
        canvas.revalidate(); canvas.repaint()
    }

    /** Export formats. */
    private fun exportGroup() = DefaultActionGroup("Export Diagram", true).apply {
        templatePresentation.icon = AllIcons.ToolbarDecorator.Export
        add(act("PNG Image…", null) { save("png") { f -> ImageIO.write(canvas.image(), "png", f.toFile()) } })
        add(act("SVG…", null) { save("svg") { f -> Files.writeString(f, DiagramExport.svg(file.diagram)) } })
        add(act("Mermaid (.md)…", null) { save("md") { f -> Files.writeString(f, "```mermaid\n" + DiagramExport.mermaid(file.diagram) + "```\n") } })
        add(act("PlantUML…", null) { save("puml") { f -> Files.writeString(f, DiagramExport.plantUml(file.diagram)) } })
        add(act("Graphviz (.dot)…", null) { save("dot") { f -> Files.writeString(f, DiagramExport.dot(file.diagram)) } })
        add(act("Graphviz with positions (.dot)…", null) { save("dot") { f -> Files.writeString(f, DiagramExport.dot(file.diagram, true)) } })
        add(act("yEd GraphML (.graphml)…", null) { save("graphml") { f -> Files.writeString(f, DiagramExport.graphml(file.diagram)) } })
        add(act("draw.io (.drawio)…", null) { save("drawio") { f -> Files.writeString(f, DiagramExport.drawio(file.diagram)) } })
        add(act("Copy Mermaid", null) { CopyPasteManager.getInstance().setContents(StringSelection(DiagramExport.mermaid(file.diagram))) })
    }

    private fun save(ext: String, write: (java.nio.file.Path) -> Unit) {
        val dlg = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Export Diagram", "", ext), panel)
        dlg.save(file.name + "." + ext)?.let { write(it.file.toPath()) }
    }

    private fun act(text: String, icon: javax.swing.Icon?, run: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = canvas
    override fun getName() = "Diagram"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file
    override fun dispose() {}
}

/** Paints node cards and orthogonal edges with labels; nodes can be dragged. */
class DiagramCanvas(private val d: Diagram, private val onMoved: () -> Unit = {}) : JComponent() {
    private var scale = 1.0
    private var dragging: dev.teo.mydb.core.diagram.DiagramNode? = null
    private var dragOffset = Point()
    private val margin = 20

    init {
        background = UIUtil.getPanelBackground()
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragging = d.nodes.lastOrNull { n -> rect(n).contains(e.point) }
                select(dragging?.id)
                dragging?.let { dragOffset = Point(e.x - sx(it.x), e.y - sy(it.y)) }
            }
            override fun mouseDragged(e: MouseEvent) {
                val n = dragging ?: return
                n.x = ((e.x - dragOffset.x - margin) / scale).coerceAtLeast(0.0)
                n.y = ((e.y - dragOffset.y - margin) / scale).coerceAtLeast(0.0)
                revalidate(); repaint()
            }
            override fun mouseReleased(e: MouseEvent) { if (dragging != null) onMoved(); dragging = null }
        }
        addMouseListener(mouse); addMouseMotionListener(mouse)
    }

    /** Moves a node (model coordinates) as a drag would, and reports the move. */
    fun moveNode(id: String, x: Double, y: Double) {
        d.node(id).let { it.x = x.coerceAtLeast(0.0); it.y = y.coerceAtLeast(0.0) }
        revalidate(); repaint(); onMoved()
    }

    var selected: String? = null
        private set
    val selectionListeners = mutableListOf<(String?) -> Unit>()

    fun select(id: String?) {
        if (id == selected) return
        selected = id
        selectionListeners.forEach { it(id) }
        repaint()
    }

    fun zoom(f: Double) = setScale(scale * f)
    fun setScale(s: Double) { scale = s.coerceIn(0.2, 4.0); revalidate(); repaint() }
    val currentScale get() = scale

    /** Scales the diagram down (never up) so it fits a [w]×[h] view, as a newly opened diagram does. */
    fun fit(w: Int, h: Int) {
        val dw = d.nodes.maxOfOrNull { it.x + it.width } ?: return
        val dh = d.nodes.maxOfOrNull { it.y + it.height } ?: return
        if (dw <= 0 || dh <= 0 || w <= margin * 2 || h <= margin * 2) return
        setScale(minOf(1.0, (w - margin * 2) / dw, (h - margin * 2) / dh))
    }

    private fun sx(x: Double) = (margin + x * scale).toInt()
    private fun sy(y: Double) = (margin + y * scale).toInt()
    private fun rect(n: dev.teo.mydb.core.diagram.DiagramNode) = Rectangle(sx(n.x), sy(n.y), (n.width * scale).toInt(), (n.height * scale).toInt())

    override fun getPreferredSize(): Dimension {
        val w = d.nodes.maxOfOrNull { it.x + it.width } ?: 0.0
        val h = d.nodes.maxOfOrNull { it.y + it.height } ?: 0.0
        return Dimension((w * scale).toInt() + margin * 2, (h * scale).toInt() + margin * 2)
    }

    fun image(): BufferedImage {
        val size = preferredSize
        val img = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = background; g.fillRect(0, 0, size.width, size.height)
        paintDiagram(g)
        g.dispose()
        return img
    }

    override fun paintComponent(g0: Graphics) {
        g0.color = background
        g0.fillRect(0, 0, width, height)
        paintDiagram(g0 as Graphics2D)
    }

    private fun paintDiagram(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // grid background
        g.color = JBColor(Color(0, 0, 0, 12), Color(255, 255, 255, 10))
        val step = (20 * scale).toInt().coerceAtLeast(5)
        for (x in 0 until width step step) g.drawLine(x, 0, x, height)
        for (y in 0 until height step step) g.drawLine(0, y, width, y)

        val font = UIUtil.getLabelFont().deriveFont((12 * scale).toFloat())
        g.font = font
        // edges: from child to parent, orthogonal with a mid break
        val solid = BasicStroke((1.4 * scale).toFloat())
        val dashed = BasicStroke((1.2 * scale).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf((5 * scale).toFloat(), (4 * scale).toFloat()), 0f)
        for (e in d.visibleEdges) {
            // a view's lines to the tables it reads are dashed
            g.stroke = if (e.kind == dev.teo.mydb.core.diagram.EdgeKind.FOREIGN_KEY) solid else dashed
            val a = rect(d.node(e.from)); val b = rect(d.node(e.to))
            val (x1, y1, x2, y2) = if (b.y + b.height <= a.y || a.y + a.height <= b.y) {
                val up = b.y < a.y
                listOf(a.centerX.toInt(), if (up) a.y else a.y + a.height, b.centerX.toInt(), if (up) b.y + b.height else b.y)
            } else {
                val left = b.x < a.x
                listOf(if (left) a.x else a.x + a.width, a.centerY.toInt(), if (left) b.x + b.width else b.x, b.centerY.toInt())
            }
            g.color = JBColor.GRAY
            val p = Path2D.Double()
            p.moveTo(x1.toDouble(), y1.toDouble())
            if (x1 == x2 || y1 == y2) p.lineTo(x2.toDouble(), y2.toDouble())
            else if (kotlin.math.abs(y1 - y2) > kotlin.math.abs(x1 - x2)) { val my = (y1 + y2) / 2.0; p.lineTo(x1.toDouble(), my); p.lineTo(x2.toDouble(), my); p.lineTo(x2.toDouble(), y2.toDouble()) }
            else { val mx = (x1 + x2) / 2.0; p.lineTo(mx, y1.toDouble()); p.lineTo(mx, y2.toDouble()); p.lineTo(x2.toDouble(), y2.toDouble()) }
            g.draw(p)
            g.stroke = solid
            arrow(g, x2, y2, if (y2 < y1) -1 else if (y2 > y1) 1 else 0, if (y1 == y2 || kotlin.math.abs(y1 - y2) <= kotlin.math.abs(x1 - x2)) (if (x2 < x1) -1 else 1) else 0)
            if (e.label.isNotEmpty()) {
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(e.label, (x1 + x2) / 2 + 4, (y1 + y2) / 2 - 2)
            }
        }
        // nodes
        val fm = g.fontMetrics
        val line = (22 * scale).toInt()
        for (n in d.nodes) {
            val r = rect(n)
            g.color = JBColor(Color(0xF7F8FA), Color(0x2B2D30)); g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8)
            g.color = JBColor(Color(0xE4E7EE), Color(0x393B40)); g.fillRoundRect(r.x, r.y, r.width, (28 * scale).toInt(), 8, 8)
            g.color = if (n.id == selected) JBColor.namedColor("Component.focusColor", JBColor(Color(0x3574F0), Color(0x548AF7))) else JBColor.border()
            g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8)
            g.color = UIUtil.getLabelForeground()
            g.font = font.deriveFont(Font.BOLD)
            g.drawString(n.title, r.x + JBUI.scale(8), r.y + (19 * scale).toInt())
            g.font = font
            n.rows.forEachIndexed { i, (icon, name, detail) ->
                val y = r.y + (28 * scale).toInt() + line * i + (16 * scale).toInt()
                g.color = when (icon) { "pk", "pk-fk" -> JBColor(Color(0xC28A00), Color(0xE0B341)); "fk" -> JBColor(Color(0x3574F0), Color(0x548AF7)); else -> UIUtil.getLabelForeground() }
                g.drawString(name, r.x + JBUI.scale(10), y)
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(detail, r.x + r.width - fm.stringWidth(detail) - JBUI.scale(8), y)
            }
        }
    }

    private fun arrow(g: Graphics2D, x: Int, y: Int, dy: Int, dx: Int) {
        val s = (7 * scale).toInt()
        val xs = if (dx != 0) intArrayOf(x, x - dx * s, x - dx * s) else intArrayOf(x, x - s / 2, x + s / 2)
        val ys = if (dx != 0) intArrayOf(y, y - s / 2, y + s / 2) else intArrayOf(y, y - dy * s, y - dy * s)
        g.fillPolygon(xs, ys, 3)
    }
}

private class ImageSelection(private val image: Image) : java.awt.datatransfer.Transferable {
    override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = image
}
