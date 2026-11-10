package com.haifasoft.haifassh.settings

import com.intellij.util.xmlb.annotations.XCollection
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession

/** Serialized state held by [SshSessionService]. */
class SshSessionState {
    @get:XCollection(style = XCollection.Style.v2)
    var folders: MutableList<SshFolder> = mutableListOf()

    @get:XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<SshSession> = mutableListOf()
}
