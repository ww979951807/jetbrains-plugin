package com.haifasoft.haifassh.fs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.haifasoft.haifassh.fs.RemoteFileManager
import com.haifasoft.haifassh.fs.RemoteFileSystem
import com.haifasoft.haifassh.fs.ftp.FtpFileSystem
import com.haifasoft.haifassh.fs.sftp.SftpFileSystem
import com.haifasoft.haifassh.ui.textRenderer
import com.haifasoft.haifassh.model.SessionProtocol
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.SshCredentialStore
import com.haifasoft.haifassh.settings.SshSessionService
import com.haifasoft.haifassh.ssh.SshConnectionHelper
import com.haifasoft.haifassh.ssh.SshConnector
import com.haifasoft.haifassh.terminal.SshTerminalManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Dedicated tool window panel for remote SFTP / FTP browsing.
 * Allows picking any configured session and connecting directly, or following
 * the currently active SSH session.
 */
class SftpBrowserToolWindowPanel(
    private val project: Project,
    private val parentDisposable: Disposable,
) : JBPanel<SftpBrowserToolWindowPanel>(BorderLayout()) {

    private val sessionCombo = ComboBox<SshSession>().apply {
        renderer = textRenderer("<No Sessions Configured>") { it.displayName() }
    }
    private val connectBtn = HoverIconButton("Connect to Session", AllIcons.Actions.Execute) { connectCurrentSession() }
    private val disconnectBtn = HoverIconButton("Disconnect Session", AllIcons.Actions.Suspend) { disconnectCurrent() }.apply { isEnabled = false }

    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)
    private val emptyPanel = JPanel(BorderLayout()).apply {
        val label = JBLabel("Select a session above and click Connect to browse remote files.", AllIcons.General.Information, JBLabel.CENTER)
        label.border = JBUI.Borders.empty(40, 20)
        add(label, BorderLayout.CENTER)
    }

    private var activeFs: RemoteFileSystem? = null
    private var browserPanel: RemoteFileBrowserPanel? = null
    private var activeDisposable: Disposable? = null

    init {
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6),
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JBLabel("Session:"))
                add(sessionCombo)
                add(HoverIconButton("Refresh Sessions", AllIcons.Actions.Refresh) { reloadSessions() })
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(connectBtn)
                add(disconnectBtn)
            }
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }

        contentPanel.add(emptyPanel, "EMPTY")
        add(header, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)

        reloadSessions()
    }

    fun reloadSessions() {
        val sessions = SshSessionService.getInstance().sessions()
        val current = sessionCombo.selectedItem as? SshSession
        sessionCombo.removeAllItems()
        sessions.forEach { sessionCombo.addItem(it) }
        if (current != null && sessions.any { it.id == current.id }) {
            sessionCombo.selectedItem = sessions.first { it.id == current.id }
        } else if (sessions.isNotEmpty()) {
            sessionCombo.selectedIndex = 0
        }
    }

    fun selectAndConnect(session: SshSession) {
        reloadSessions()
        for (i in 0 until sessionCombo.itemCount) {
            if (sessionCombo.getItemAt(i).id == session.id) {
                sessionCombo.selectedIndex = i
                break
            }
        }
        connectCurrentSession()
    }

    private fun connectCurrentSession() {
        val session = sessionCombo.selectedItem as? SshSession ?: return
        disconnectCurrent()

        connectBtn.isEnabled = false
        disconnectBtn.isEnabled = true

        val childDisposable = Disposer.newDisposable("ToolWindow-SFTP-${session.id}")
        activeDisposable = childDisposable
        Disposer.register(parentDisposable, childDisposable)

        if (session.protocol == SessionProtocol.FTP) {
            connectFtp(session, childDisposable)
        } else {
            connectSftp(session, childDisposable)
        }
    }

    private fun connectFtp(session: SshSession, disposable: Disposable) {
        val password = SshCredentialStore.getPassword(session.id)
        object : Task.Backgroundable(project, "Connecting to FTP ${session.displayName()}", true) {
            private var fs: FtpFileSystem? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                fs = FtpFileSystem.connect(session.host, session.port, session.username, password)
            }

            override fun onSuccess() {
                val opened = fs ?: return
                displayFs(session, opened, disposable)
            }

            override fun onThrowable(error: Throwable) {
                fs?.disconnect()
                disconnectCurrent()
                RemoteFileManager.getInstance(project).notifyError("FTP connect failed: ${error.message}")
            }
        }.queue()
    }

    private fun connectSftp(session: SshSession, disposable: Disposable) {
        val chain = SshConnectionHelper.buildChain(project, session)
        if (chain == null) {
            disconnectCurrent()
            return
        }

        object : Task.Backgroundable(project, "Connecting to SFTP ${session.displayName()}", true) {
            private var fs: SftpFileSystem? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                fs = SshConnector.openSftp(chain, SshConnectionHelper.hostKeyPrompt(project))
            }

            override fun onSuccess() {
                val opened = fs ?: return
                displayFs(session, opened, disposable)
            }

            override fun onThrowable(error: Throwable) {
                fs?.disconnect()
                disconnectCurrent()
                RemoteFileManager.getInstance(project).notifyError("SFTP connect failed: ${error.message}")
            }
        }.queue()
    }

    private fun displayFs(session: SshSession, fs: RemoteFileSystem, disposable: Disposable) {
        activeFs = fs
        val browser = RemoteFileBrowserPanel(
            project = project,
            session = session,
            fs = fs,
            parentDisposable = disposable,
            onOpenTerminal = if (session.protocol != SessionProtocol.FTP) {
                { SshTerminalManager.getInstance(project).connect(session) }
            } else null,
        )
        browserPanel = browser
        contentPanel.add(browser, "BROWSER")
        cardLayout.show(contentPanel, "BROWSER")
        connectBtn.isEnabled = false
        disconnectBtn.isEnabled = true
    }

    fun disconnectCurrent() {
        activeFs?.disconnect()
        activeFs = null
        activeDisposable?.let { Disposer.dispose(it) }
        activeDisposable = null
        browserPanel = null
        cardLayout.show(contentPanel, "EMPTY")
        connectBtn.isEnabled = true
        disconnectBtn.isEnabled = false
    }
}
