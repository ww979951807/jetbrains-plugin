package com.haifasoft.haifassh.fs.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.haifasoft.haifassh.fs.ui.RemoteFileBrowserPanel
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

class RemoteFileBrowserFileEditor(
    private val project: Project,
    private val file: RemoteFileBrowserVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val disposable = Disposer.newDisposable("HaifaSSH FileBrowser ${file.session.displayName()}")
    private val panel = RemoteFileBrowserPanel(
        project = project,
        session = file.session,
        fs = file.fs,
        parentDisposable = disposable,
        onOpenTerminal = file.onOpenTerminal,
    )
    private val changeSupport = PropertyChangeSupport(this)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = file.session.displayName()

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getCurrentLocation() = null

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    override fun dispose() {
        Disposer.dispose(disposable)
        ApplicationManager.getApplication().invokeLater {
            val stillOpen = !project.isDisposed &&
                runCatching { FileEditorManager.getInstance(project).isFileOpen(file) }.getOrDefault(false)
            if (!stillOpen) file.tearDown()
        }
    }
}
