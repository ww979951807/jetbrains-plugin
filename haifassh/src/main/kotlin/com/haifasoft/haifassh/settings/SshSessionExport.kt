package com.haifasoft.haifassh.settings

import com.intellij.util.xmlb.annotations.XCollection
import com.haifasoft.haifassh.model.SshAuthType
import com.haifasoft.haifassh.model.SshFolder

/**
 * Portable, self-contained representation of the session tree for import/export.
 * Unlike the live [SshSessionState], a bundle may optionally carry secrets inline
 * (when the user opts in) so configurations can be moved between machines.
 *
 * Mutable with defaults so the IntelliJ XML serializer can read/write it.
 */
class SshExportBundle {
    /** Bumped if the on-disk format changes incompatibly. */
    var formatVersion: Int = 1

    /** True when [SshSessionExport.password]/passphrase fields are populated. */
    var includesSecrets: Boolean = false

    @get:XCollection(style = XCollection.Style.v2)
    var folders: MutableList<SshFolder> = mutableListOf()

    @get:XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<SshSessionExport> = mutableListOf()
}

/** A session plus its optional inline secrets, for transport only. */
class SshSessionExport {
    var id: String = ""
    var name: String = ""
    var folderId: String = ""
    var host: String = ""
    var port: Int = 22
    var username: String = ""
    var authType: SshAuthType = SshAuthType.PASSWORD
    var privateKeyPath: String = ""
    var protocol: com.haifasoft.haifassh.model.SessionProtocol = com.haifasoft.haifassh.model.SessionProtocol.SSH
    var savePassword: Boolean = true
    var allowLegacyAlgorithms: Boolean = false
    var iconKey: String = ""
    var color: String = ""

    /** Manual order within [folderId]; absent in pre-0.12 exports, which import as 0. */
    var position: Int = 0

    @get:XCollection(style = XCollection.Style.v2)
    var jumpHosts: MutableList<JumpHostExport> = mutableListOf()

    /** Populated only when the user exports with credentials included. */
    var password: String? = null
    var passphrase: String? = null
}

/** A jump host plus its optional inline secrets, for transport only. */
class JumpHostExport {
    var host: String = ""
    var port: Int = 22
    var username: String = ""
    var authType: SshAuthType = SshAuthType.PASSWORD
    var privateKeyPath: String = ""
    var password: String? = null
    var passphrase: String? = null
}
