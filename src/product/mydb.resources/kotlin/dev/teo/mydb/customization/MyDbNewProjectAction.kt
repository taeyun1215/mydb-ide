package dev.teo.mydb.customization

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Creates an empty directory-based project and opens it. The project holds consoles, scripts and
 * data source settings.
 */
class MyDbNewProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = NewProjectDialog()
        if (!dialog.showAndGet()) return
        val dir = dialog.projectDir()
        Files.createDirectories(dir)
        ProjectManagerEx.getInstanceEx().openProject(dir, OpenProjectTask {
            isNewProject = true
            projectName = dir.fileName.toString()
            forceOpenInNewFrame = e.project != null
        })
    }

    companion object {
        /** Default parent directory of new projects. */
        fun defaultLocation(): Path = Path.of(System.getProperty("user.home"), "MyDBProjects")

        /** First free `<base><n>` name under [parent]: "untitled", "untitled1", ... */
        fun suggestName(parent: Path, base: String = "untitled"): String =
            generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base$it" }.first { !Files.exists(parent.resolve(it)) }

        /** Problem with a proposed project name, or null when it is usable. */
        fun validateName(parent: Path, name: String): String? = when {
            name.isBlank() -> "Enter a project name"
            name.any { it in "/\\:*?\"<>|" } -> "The name contains characters that cannot be used in a folder name"
            Files.exists(parent.resolve(name)) && Files.list(parent.resolve(name)).use { it.findAny().isPresent } ->
                "A non-empty folder with this name already exists"
            else -> null
        }
    }

    private class NewProjectDialog : DialogWrapper(true) {
        private val location = TextFieldWithBrowseButton().apply {
            text = defaultLocation().toString()
            addBrowseFolderListener(null, FileChooserDescriptorFactory.singleDir().withTitle("Project Location"))
        }
        private val name = JBTextField(suggestName(defaultLocation()))

        init {
            title = "New Project"
            init()
        }

        fun projectDir(): Path = Path.of(location.text.trim()).resolve(name.text.trim())

        override fun createCenterPanel(): JComponent = panel {
            row("Name:") { cell(name).align(AlignX.FILL).focused() }
            row("Location:") { cell(location).align(AlignX.FILL) }
        }.also { it.preferredSize = java.awt.Dimension(480, it.preferredSize.height) }

        override fun doValidate(): ValidationInfo? {
            val parent = try { Path.of(location.text.trim()) } catch (_: InvalidPathException) {
                return ValidationInfo("Invalid location", location)
            }
            return validateName(parent, name.text.trim())?.let { ValidationInfo(it, name) }
        }
    }
}
