package com.haifasoft.haifassh.fs.editor

import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.testFramework.LightVirtualFile
import com.haifasoft.haifassh.fs.RemoteFileSystem
import com.haifasoft.haifassh.model.SshSession

class RemoteFileBrowserVirtualFile(
    val session: SshSession,
    val fs: RemoteFileSystem,
    val onOpenTerminal: (() -> Unit)? = null,
) : LightVirtualFile("${session.displayName()} [Files]", FileTypes.UNKNOWN, "") {

    private var tornDown = false

    init {
        isWritable = false
    }

    @Synchronized
    fun tearDown() {
        if (tornDown) return
        tornDown = true
        runCatching { fs.disconnect() }
    }
}
