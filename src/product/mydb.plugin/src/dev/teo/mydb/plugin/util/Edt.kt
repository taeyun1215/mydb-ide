package dev.teo.mydb.plugin.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Delivers a topic event on the EDT: our topic listeners update Swing trees and tabs, while sessions
 * open and introspection finishes on background threads.
 */
fun publishOnEdt(project: Project, publish: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) publish() else app.invokeLater({ if (!project.isDisposed) publish() }, project.disposed)
}
