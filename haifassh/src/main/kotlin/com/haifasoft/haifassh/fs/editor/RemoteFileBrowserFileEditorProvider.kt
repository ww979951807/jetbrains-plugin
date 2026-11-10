package com.haifasoft.haifassh.fs.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class RemoteFileBrowserFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is RemoteFileBrowserVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        RemoteFileBrowserFileEditor(project, file as RemoteFileBrowserVirtualFile)

    override fun getEditorTypeId(): String = "haifassh-file-browser"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
