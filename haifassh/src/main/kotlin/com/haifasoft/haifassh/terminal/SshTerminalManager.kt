package com.haifasoft.haifassh.terminal

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.haifasoft.haifassh.model.SshAuthType
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.settings.SshCredentialStore
import com.haifasoft.haifassh.ssh.HopConfig
import com.haifasoft.haifassh.ssh.SshConnectionHelper
import com.haifasoft.haifassh.ssh.SshConnector
import com.haifasoft.haifassh.ssh.SshShellHandle
import com.haifasoft.haifassh.terminal.editor.SshTerminalVirtualFile

/**
 * Connects sessions and surfaces each live shell as a tab in the
 * "SSH Terminals" tool window — the MobaXterm-style multi-session terminal area.
 */
@Service(Service.Level.PROJECT)
class SshTerminalManager(private val project: Project) {

    fun connect(session: SshSession) {
        if (session.host.isBlank()) {
            notifyError("Session \"${session.displayName()}\" has no host configured.")
            return
        }

        // Resolve secrets (may prompt) on the EDT before going to a background thread.
        val chain = SshConnectionHelper.buildChain(project, session) ?: return

        object : Task.Backgroundable(project, "Connecting to ${session.displayName()}", true) {
            private var handle: SshShellHandle? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                handle = SshConnector.openShell(chain, "xterm-256color", 80, 24, SshConnectionHelper.hostKeyPrompt(project))
            }

            override fun onSuccess() {
                handle?.let { open(session, it) }
            }

            override fun onThrowable(error: Throwable) {
                handle?.close()
                notifyError("Failed to connect to ${session.displayName()}: ${error.message ?: error.javaClass.simpleName}")
            }
        }.queue()
    }

    /** Presents the live shell either as an editor tab or in the bottom tool window, per settings. */
    private fun open(session: SshSession, handle: SshShellHandle) {
        if (HaifaSshSettings.getInstance().openInEditor) {
            openInEditorArea(session, handle)
        } else {
            openTerminalTab(session, handle)
        }
    }

    /** Opens the terminal as an editor tab, where source files normally open. */
    private fun openInEditorArea(session: SshSession, handle: SshShellHandle) {
        val file = SshTerminalVirtualFile(session, handle)
        // The terminal widget (and its lifecycle) is built by SshTerminalFileEditorProvider.
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Opens the terminal as a tab in the bottom "SSH Terminals" tool window. */
    private fun openTerminalTab(session: SshSession, handle: SshShellHandle) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINALS_TOOL_WINDOW_ID)
        if (toolWindow == null) {
            handle.close()
            notifyError("SSH Terminals tool window is not available.")
            return
        }

        // Tie the widget's theme-listener lifetime to the content; disposed when the tab closes.
        val lifetime = Disposer.newDisposable("HaifaSSH terminal ${session.displayName()}")
        val pane = SshTerminalUi.createPane(project, session, handle, lifetime)

        val content = ContentFactory.getInstance().createContent(pane.component, session.displayName(), false)
        content.isCloseable = true
        content.setDisposer(Disposable {
            runCatching { pane.dispose() }
            Disposer.dispose(lifetime)
            handle.close()
        })

        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        toolWindow.activate { pane.preferredFocus.requestFocusInWindow() }
    }

    private fun notifyError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.ERROR)
                .notify(project)
        }
    }

    companion object {
        const val TERMINALS_TOOL_WINDOW_ID = "SSH Terminals"
        const val NOTIFICATION_GROUP_ID = "HaifaSSH"

        fun getInstance(project: Project): SshTerminalManager = project.getService(SshTerminalManager::class.java)
    }
}
