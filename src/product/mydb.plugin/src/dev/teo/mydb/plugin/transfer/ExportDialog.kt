package dev.teo.mydb.plugin.transfer

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.teo.mydb.core.transfer.ExportOptions
import dev.teo.mydb.core.transfer.Extractor
import dev.teo.mydb.core.transfer.Extractors
import dev.teo.mydb.core.transfer.TableData
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JSplitPane
import javax.swing.event.DocumentEvent

/**
 * "Export Data": source, extractor and options on the left, a live preview of the
 * first rows on the right; copy to the clipboard or write the file.
 */
class ExportDialog(private val project: Project, private val data: TableData, initial: Extractor = Extractors.byId("csv")) :
    DialogWrapper(project, true) {

    private val source = data.sourceTable ?: "Query result"
    private val extractor = ComboBox(Extractors.all.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.title }
        selectedItem = initial
    }
    private val transpose = JBCheckBox("Transpose")
    private val columnHeader = JBCheckBox("Add column header", dev.teo.mydb.plugin.settings.MyDbSettings.get().csvHeaderByDefault)
    private val rowHeader = JBCheckBox("Add row header")
    private val output = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Output File"))
    }
    private val preview = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12) }
    private var pathEdited = false

    private val copyAction = object : DialogWrapperAction("Copy to Clipboard") {
        override fun doAction(e: ActionEvent?) {
            val text = DataExport.text(selectedExtractor(), data, options())
            if (text == null) { Messages.showErrorDialog(project, "${selectedExtractor().title} output is binary and cannot be copied as text.", "Export Data"); return }
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = "Export Data"
        output.text = DataExport.defaultPath(source, initial).toString()
        extractor.addActionListener { onExtractorChanged() }
        listOf(transpose, columnHeader, rowHeader).forEach { it.addActionListener { refresh() } }
        output.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (output.textField.hasFocus()) pathEdited = true; updateButtons() }
        })
        init()
        refresh()
    }

    fun selectedExtractor(): Extractor = extractor.selectedItem as Extractor
    fun options() = ExportOptions(transpose.isSelected, columnHeader.isSelected && columnHeader.isEnabled, rowHeader.isSelected && rowHeader.isEnabled)
    fun outputPath(): Path? = try { Path.of(output.text.trim()) } catch (_: InvalidPathException) { null }

    private fun onExtractorChanged() {
        if (!pathEdited) output.text = DataExport.defaultPath(source, selectedExtractor()).toString()
        refresh()
    }

    private fun refresh() {
        val headers = selectedExtractor().supportsHeaders
        columnHeader.isEnabled = headers
        rowHeader.isEnabled = headers
        preview.text = runCatching { DataExport.preview(selectedExtractor(), data, options()) }.getOrElse { "Preview failed: ${it.message}" }
        preview.caretPosition = 0
        updateButtons()
    }

    private fun updateButtons() {
        isOKActionEnabled = DataExport.isWritableTarget(outputPath())
        copyAction.isEnabled = selectedExtractor().let { it.id != "xlsx" }
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, copyAction, okAction.also { it.putValue(Action.NAME, "Export to File") })

    override fun doOKAction() {
        val path = outputPath() ?: return
        try {
            DataExport.write(path, selectedExtractor(), data, options())
            super.doOKAction()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Export Data")
        }
    }

    override fun createCenterPanel(): JComponent {
        val left = panel {
            row("Source:") { label(source) }
            row("Extractor:") { cell(extractor).align(AlignX.FILL) }
            row { cell(transpose) }
            row { cell(columnHeader) }
            row { cell(rowHeader) }
            row("Output file:") { cell(output).align(AlignX.FILL) }
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, JBScrollPane(preview)).apply {
            resizeWeight = 0.35
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(420))
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = extractor
}
