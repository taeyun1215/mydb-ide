package dev.teo.mydb.customization

import com.intellij.openapi.wm.ToolWindowId
import com.intellij.toolWindow.DefaultToolWindowLayoutBuilder
import com.intellij.toolWindow.DefaultToolWindowLayoutExtension

/**
 * Default layout of a new project: database explorer open on the left,
 * the file view (project root, scratches and consoles) open on the right.
 */
class MyDbToolWindowLayout : DefaultToolWindowLayoutExtension {
    override fun buildV1Layout(builder: DefaultToolWindowLayoutBuilder) = build(builder)
    override fun buildV2Layout(builder: DefaultToolWindowLayoutBuilder) = build(builder)

    private fun build(builder: DefaultToolWindowLayoutBuilder) {
        builder.removeAll { it.id == ToolWindowId.PROJECT_VIEW || it.id == EXPLORER }
        builder.left.addOrUpdate(EXPLORER) { isVisible = true; weight = 0.25f }
        builder.right.addOrUpdate(ToolWindowId.PROJECT_VIEW) { isVisible = true; weight = 0.2f }
    }

    private companion object {
        const val EXPLORER = "MyDB Explorer"
    }
}
