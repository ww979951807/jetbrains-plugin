package com.haifasoft.haifassh.terminal

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.jediterm.terminal.ui.JediTermWidget
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.settings.SshCommandHistoryService
import com.haifasoft.haifassh.ssh.SshShellHandle
import com.haifasoft.haifassh.terminal.assist.SshTerminalPane

/**
 * Builds the JediTerm widget for a live SSH shell and wires up theme-following.
 * Shared by the editor-tab and tool-window presentations so both look identical.
 */
object SshTerminalUi {

    /**
     * Creates a started terminal widget bound to [handle]. The widget follows the
     * IDE theme (see [IdeTerminalSettingsProvider]) and re-paints when the IDE
     * Look-and-Feel changes, for as long as [parentDisposable] is alive. Keystrokes
     * are observed by [recorder] (when non-null) to build the command history.
     */
    fun createWidget(
        session: SshSession,
        handle: SshShellHandle,
        parentDisposable: Disposable,
        recorder: TerminalCommandRecorder? = null,
    ): JediTermWidget {
        val widget = ThemedJediTermWidget(IdeTerminalSettingsProvider())
        widget.setTtyConnector(SshTtyConnector(session.displayName(), handle, recorder))
        widget.start()

        // Re-theme open terminals when the IDE switches dark/light or the user picks
        // a different terminal color scheme in settings.
        val connection = ApplicationManager.getApplication().messageBus.connect(parentDisposable)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            runCatching { widget.reapplyScheme() }
        })
        connection.subscribe(HaifaSshSettings.SCHEME_TOPIC, HaifaSshSettings.SchemeListener {
            runCatching { widget.reapplyScheme() }
        })

        return widget
    }

    /**
     * Creates a terminal widget for [session]/[handle] together with its companion
     * assistant panel (History + Commands), wrapped in a re-positionable splitter.
     * This is what both terminal presentations mount. Command history is recorded
     * per host into [SshCommandHistoryService].
     */
    fun createPane(
        project: Project?,
        session: SshSession,
        handle: SshShellHandle,
        parentDisposable: Disposable,
    ): SshTerminalPane {
        val hostKey = SshCommandHistoryService.getInstance().hostKey(session.host)
        val widget = createWidget(session, handle, parentDisposable, TerminalCommandRecorder(hostKey))
        return SshTerminalPane(widget, project, session, hostKey, parentDisposable, handle)
    }
}
