package com.haifasoft.haifassh.terminal.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Routes [SshTerminalVirtualFile]s to an [SshTerminalFileEditor] so SSH terminals
 * open in the editor area. Only accepts our own virtual files, so it never
 * interferes with normal source files.
 */
class SshTerminalFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is SshTerminalVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        SshTerminalFileEditor(project, file as SshTerminalVirtualFile)

    override fun getEditorTypeId(): String = "haifassh-terminal"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
