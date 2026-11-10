package com.haifasoft.haifassh.terminal.assist

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.fs.sftp.SftpFileSystem
import com.haifasoft.haifassh.fs.ui.RemoteFileBrowserPanel
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.ssh.SshConnectionHelper
import com.haifasoft.haifassh.ssh.SshConnector
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

import com.haifasoft.haifassh.ssh.SshShellHandle

/**
 * An SFTP File Browser tab hosted inside the split Terminal Assistant panel.
 * Reuses the active SSH session from [handle] when available so it does not
 * re-authenticate or open another connection.
 */
class SftpTab(
    private val project: Project,
    private val session: SshSession,
    private val parentDisposable: Disposable,
    private val handle: SshShellHandle? = null,
    private val typeText: ((String) -> Unit)? = null,
) : JBPanel<SftpTab>(BorderLayout()) {

    private var activeFs: SftpFileSystem? = null
    private val loadingPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
        border = JBUI.Borders.empty(20)
        add(AsyncProcessIcon("Connecting SFTP"))
        add(JBLabel(" Connecting to SFTP..."))
    }

    init {
        connectSftp()
    }

    private fun connectSftp() {
        removeAll()
        add(loadingPanel, BorderLayout.CENTER)
        revalidate()
        repaint()

        object : Task.Backgroundable(project, "Connecting to SFTP ${session.displayName()}", true) {
            private var fs: SftpFileSystem? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Priority 1: Reuse existing terminal session (instant, no extra handshake)
                if (handle != null && handle.isConnected) {
                    try {
                        val sftpChannel = handle.openSftpChannel()
                        fs = SftpFileSystem(sftpChannel, sessions = emptyList(), closeSessionsOnDisconnect = false)
                        return
                    } catch (_: Throwable) {
                        // Fall back to opening a dedicated channel below
                    }
                }

                // Priority 2: Establish dedicated SFTP session
                val chain = SshConnectionHelper.buildChain(project, session)
                    ?: throw IllegalStateException("Failed to resolve connection credentials")
                fs = SshConnector.openSftp(chain, SshConnectionHelper.hostKeyPrompt(project))
            }

            override fun onSuccess() {
                val opened = fs ?: return
                activeFs = opened
                Disposer.register(parentDisposable, Disposable {
                    opened.disconnect()
                })

                removeAll()
                val browser = RemoteFileBrowserPanel(
                    project = project,
                    session = session,
                    fs = opened,
                    parentDisposable = parentDisposable,
                    onCdInTerminal = if (typeText != null) { path -> typeText.invoke("cd \"$path\"\n") } else null,
                )
                add(browser, BorderLayout.CENTER)
                revalidate()
                repaint()
            }

            override fun onThrowable(error: Throwable) {
                fs?.disconnect()
                showRetry("Connection failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }.queue()
    }

    private fun showRetry(message: String) {
        removeAll()
        val retryPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            val label = JBLabel(message).apply {
                icon = AllIcons.General.Warning
            }
            val retryBtn = JButton("Retry Connection", AllIcons.Actions.Refresh).apply {
                addActionListener { connectSftp() }
            }
            add(label, BorderLayout.CENTER)
            add(retryPanelWrap(retryBtn), BorderLayout.SOUTH)
        }
        add(retryPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun retryPanelWrap(btn: JButton): JPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
        add(btn)
    }
}
