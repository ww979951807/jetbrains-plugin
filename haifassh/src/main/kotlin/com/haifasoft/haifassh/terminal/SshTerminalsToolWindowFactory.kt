package com.haifasoft.haifassh.terminal

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Hosts the live SSH terminal tabs. Starts empty; [SshTerminalManager] adds a
 * tab each time a session connects.
 */
class SshTerminalsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Intentionally empty: terminal tabs are created on demand when a session connects.
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
