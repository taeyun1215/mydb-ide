package dev.teo.mydb.customization

import com.intellij.openapi.fileEditor.impl.EditorEmptyTextProvider
import com.intellij.openapi.fileEditor.impl.EditorEmptyTextSink
import javax.swing.JComponent

/** Shortcut hints in the empty editor area start with data source management. */
class MyDbEditorEmptyText : EditorEmptyTextProvider {
    override fun appendEmptyText(splitters: JComponent, sink: EditorEmptyTextSink) {
        sink.appendActionWithFirstKeyboardShortcut("Data Sources", "MyDb.DataSources")
        sink.appendActionWithFirstKeyboardShortcut("New Query Console", "MyDb.NewConsole")
    }
}
