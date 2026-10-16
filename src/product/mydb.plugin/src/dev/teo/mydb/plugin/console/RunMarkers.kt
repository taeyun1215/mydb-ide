package dev.teo.mydb.plugin.console

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Icon

/**
 * After a run, each executed statement gets a gutter icon (succeeded / failed) on its first line and its
 * run time after its end. Markers are replaced by the next run.
 */
object RunMarkers {
    private val MARKERS = Key.create<MutableList<Any>>("mydb.run.markers")

    fun show(project: Project, file: VirtualFile, runs: List<ConsoleRunner.StatementRun>) {
        for (e in FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().map { it.editor }) show(e, runs)
    }

    fun show(editor: Editor, runs: List<ConsoleRunner.StatementRun>) {
        clear(editor)
        val doc = editor.document
        val created = mutableListOf<Any>()
        for (run in runs) {
            val start = run.statement.range.first.coerceIn(0, doc.textLength)
            val end = (run.statement.range.last + 1).coerceIn(start, doc.textLength)
            val line = doc.getLineNumber(start)
            val h = editor.markupModel.addRangeHighlighter(doc.getLineStartOffset(line), doc.getLineEndOffset(line),
                HighlighterLayer.ADDITIONAL_SYNTAX, TextAttributes(), com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE)
            h.gutterIconRenderer = StatusIcon(run.ok, run.millis)
            created += h
            if (dev.teo.mydb.plugin.settings.MyDbSettings.get().showExecutionTime)
                editor.inlayModel.addAfterLineEndElement(end, true, TimeRenderer("${run.millis} ms"))?.let { created += it }
        }
        editor.putUserData(MARKERS, created)
    }

    fun clear(editor: Editor) {
        editor.getUserData(MARKERS)?.forEach { m ->
            when (m) {
                is RangeHighlighter -> editor.markupModel.removeHighlighter(m)
                is Inlay<*> -> Disposer.dispose(m)
            }
        }
        editor.putUserData(MARKERS, null)
    }

    /** Markers currently shown, for tests: (gutter tooltips, inlay texts). */
    fun shown(editor: Editor): Pair<List<String>, List<String>> {
        val ms = editor.getUserData(MARKERS).orEmpty()
        return ms.filterIsInstance<RangeHighlighter>().mapNotNull { it.gutterIconRenderer?.tooltipText } to
            ms.filterIsInstance<Inlay<*>>().mapNotNull { (it.renderer as? TimeRenderer)?.text }
    }

    private class StatusIcon(private val ok: Boolean, private val millis: Long) : GutterIconRenderer() {
        override fun getIcon(): Icon = if (ok) AllIcons.RunConfigurations.TestPassed else AllIcons.RunConfigurations.TestError
        override fun getTooltipText() = if (ok) "Executed in $millis ms" else "Failed after $millis ms"
        override fun equals(other: Any?) = other is StatusIcon && other.ok == ok && other.millis == millis
        override fun hashCode() = ok.hashCode() * 31 + millis.hashCode()
    }

    class TimeRenderer(val text: String) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val metrics = inlay.editor.contentComponent.getFontMetrics(inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN))
            return metrics.stringWidth("  $text")
        }
        override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g.color = JBColor.GRAY
            g.drawString("  $text", r.x, r.y + editor.ascent)
        }
    }
}
