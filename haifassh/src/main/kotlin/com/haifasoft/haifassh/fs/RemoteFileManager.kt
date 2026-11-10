package com.haifasoft.haifassh.fs

import com.haifasoft.haifassh.fs.editor.RemoteFileBrowserVirtualFile
import com.haifasoft.haifassh.fs.ftp.FtpFileSystem
import com.haifasoft.haifassh.fs.sftp.SftpFileSystem
import com.haifasoft.haifassh.fs.ui.RemoteFileBrowserPanel
import com.haifasoft.haifassh.model.SessionProtocol
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.settings.SshCredentialStore
import com.haifasoft.haifassh.ssh.SshConnectionHelper
import com.haifasoft.haifassh.ssh.SshConnector
import com.haifasoft.haifassh.terminal.SshTerminalManager
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

@Service(Service.Level.PROJECT)
class RemoteFileManager(private val project: Project) {

    fun openBrowser(session: SshSession) {
        if (session.host.isBlank()) {
            notifyError("Session \"${session.displayName()}\" has no host configured.")
            return
        }

        if (session.protocol == SessionProtocol.FTP) {
            connectFtp(session)
        } else {
            connectSftp(session)
        }
    }

    private fun connectFtp(session: SshSession) {
        var password = SshCredentialStore.getPassword(session.id)
        if (password.isNullOrEmpty()) {
            password = Messages.showPasswordDialog(
                project,
                "FTP password for ${session.username}@${session.host}:",
                "FTP Authentication",
                null,
            ) ?: return
        }

        object : Task.Backgroundable(project, "Connecting to FTP ${session.displayName()}", true) {
            private var fs: FtpFileSystem? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                fs = FtpFileSystem.connect(session.host, session.port, session.username, password)
            }

            override fun onSuccess() {
                fs?.let { display(session, it) }
            }

            override fun onThrowable(error: Throwable) {
                fs?.disconnect()
                notifyError("Failed to connect FTP ${session.displayName()}: ${error.message ?: error.javaClass.simpleName}")
            }
        }.queue()
    }

    private fun connectSftp(session: SshSession) {
        val chain = SshConnectionHelper.buildChain(project, session) ?: return

        object : Task.Backgroundable(project, "Connecting to SFTP ${session.displayName()}", true) {
            private var fs: SftpFileSystem? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                fs = SshConnector.openSftp(chain, SshConnectionHelper.hostKeyPrompt(project))
            }

            override fun onSuccess() {
                fs?.let { display(session, it) }
            }

            override fun onThrowable(error: Throwable) {
                fs?.disconnect()
                notifyError("Failed to connect SFTP ${session.displayName()}: ${error.message ?: error.javaClass.simpleName}")
            }
        }.queue()
    }

    private fun display(session: SshSession, fs: RemoteFileSystem) {
        val onOpenTerminal: (() -> Unit)? = if (session.protocol != SessionProtocol.FTP) {
            { SshTerminalManager.getInstance(project).connect(session) }
        } else null

        if (HaifaSshSettings.getInstance().openInEditor) {
            val file = RemoteFileBrowserVirtualFile(session, fs, onOpenTerminal)
            FileEditorManager.getInstance(project).openFile(file, true)
        } else {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SshTerminalManager.TERMINALS_TOOL_WINDOW_ID)
            if (toolWindow == null) {
                val file = RemoteFileBrowserVirtualFile(session, fs, onOpenTerminal)
                FileEditorManager.getInstance(project).openFile(file, true)
                return
            }

            val lifetime = Disposer.newDisposable("HaifaSSH FileBrowser ${session.displayName()}")
            val panel = RemoteFileBrowserPanel(project, session, fs, lifetime, onOpenTerminal)

            val content = ContentFactory.getInstance().createContent(panel, "${session.displayName()} [Files]", false)
            content.isCloseable = true
            content.setDisposer(Disposable {
                Disposer.dispose(lifetime)
                fs.disconnect()
            })

            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.activate(null)
        }
    }

    fun openInToolWindow(session: SshSession) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(com.haifasoft.haifassh.fs.ui.SftpBrowserToolWindowFactory.TOOL_WINDOW_ID)
        toolWindow?.activate {
            com.haifasoft.haifassh.fs.ui.SftpBrowserToolWindowFactory.panelInstance?.selectAndConnect(session)
        }
    }

    fun notifyError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(SshTerminalManager.NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.ERROR)
                .notify(project)
        }
    }

    companion object {
        fun getInstance(project: Project): RemoteFileManager = project.getService(RemoteFileManager::class.java)
    }
}
