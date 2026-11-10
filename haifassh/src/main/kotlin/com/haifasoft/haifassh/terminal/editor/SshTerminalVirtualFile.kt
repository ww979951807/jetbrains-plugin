package com.haifasoft.haifassh.terminal.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.ssh.SshShellHandle
import com.haifasoft.haifassh.terminal.SshTerminalUi
import com.haifasoft.haifassh.terminal.assist.SshTerminalPane

/**
 * An in-memory virtual file that represents one live SSH connection, so the
 * terminal can be opened as an editor tab via [com.intellij.openapi.fileEditor.FileEditorManager].
 *
 * Carries the already-opened [handle]; [SshTerminalFileEditorProvider] reads it
 * when the platform asks it to build the editor. Each connection gets its own
 * instance (identity equality) so repeated connects open separate tabs.
 *
 * The live terminal pane is owned HERE, not by the editor: dragging a tab to
 * another split/window makes the platform dispose the old [SshTerminalFileEditor]
 * and create a new one for the same file, so anything owned by the editor
 * (widget buffer, SSH connection) would be destroyed mid-move. The pane is
 * created once on first request and torn down only via [tearDown] when the file
 * is closed everywhere.
 *
 * Because that one pane is a single Swing component, it can live in only one
 * editor split at a time; this file also arbitrates which [SshTerminalEditorView]
 * currently displays it.
 */
class SshTerminalVirtualFile(
    val session: SshSession,
    val handle: SshShellHandle,
) : LightVirtualFile(session.displayName(), FileTypes.UNKNOWN, "") {

    private var pane: SshTerminalPane? = null
    private var lifetime: Disposable? = null
    private var tornDown = false

    init {
        isWritable = false
    }

    /** Returns the live terminal pane, creating it on first call. */
    @Synchronized
    fun acquirePane(project: Project): SshTerminalPane {
        pane?.let { return it }
        val life = Disposer.newDisposable("HaifaSSH terminal ${session.displayName()}")
        val created = SshTerminalUi.createPane(project, session, handle, life)
        pane = created
        lifetime = life
        return created
    }

    // ---- Which split shows the terminal ------------------------------------

    private val views = mutableListOf<SshTerminalEditorView>()
    private var showingIn: SshTerminalEditorView? = null

    /** Registers a newly opened editor's view. The first one to arrive gets the terminal. */
    fun addView(view: SshTerminalEditorView) {
        if (!views.contains(view)) views.add(view)
        if (showingIn == null) showTerminalIn(view)
    }

    /** Unregisters a closing editor's view, handing the terminal to a split that is left. */
    fun removeView(view: SshTerminalEditorView) {
        views.remove(view)
        if (showingIn === view) {
            showingIn = null
            views.lastOrNull()?.let { showTerminalIn(it) }
        }
    }

    /**
     * Moves the live terminal into [view]. Whichever split had it falls back to its
     * placeholder first, so the component is only ever inside one container.
     */
    fun showTerminalIn(view: SshTerminalEditorView) {
        if (tornDown || view !in views) return
        if (showingIn === view && view.holdsTerminal) return
        showingIn?.takeIf { it !== view }?.showPlaceholder()
        showingIn = view
        view.showTerminal()
    }

    /** Closes the widget, its listeners, and the SSH connection. Idempotent. */
    @Synchronized
    fun tearDown() {
        if (tornDown) return
        tornDown = true
        runCatching { pane?.dispose() }
        lifetime?.let { Disposer.dispose(it) }
        runCatching { handle.close() }
    }
}
