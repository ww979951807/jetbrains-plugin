package com.haifasoft.haifassh.model

import com.intellij.util.xmlb.annotations.XCollection

/**
 * A saved SSH session. Secrets (password, key passphrase, jump-host passwords)
 * are NOT stored here — they live in the IDE [com.intellij.ide.passwordSafe.PasswordSafe]
 * keyed by this session's [id]. See com.haifasoft.haifassh.settings.SshCredentialStore.
 *
 * Mutable with defaults so the IntelliJ XML serializer can persist it.
 */
class SshSession {
    var id: String = ""
    var name: String = ""

    /** Empty means the session lives at the tree root. */
    var folderId: String = ""

    var host: String = ""
    var port: Int = 22
    var username: String = ""

    var authType: SshAuthType = SshAuthType.PASSWORD
    var privateKeyPath: String = ""
    
    var protocol: SessionProtocol = SessionProtocol.SSH

    /** When true, password / passphrase are persisted in PasswordSafe; otherwise prompted each connect. */
    var savePassword: Boolean = true

    /**
     * Opt-in fallback to legacy SHA-1/DSA algorithms (`ssh-rsa`, `ssh-dss`) for
     * old servers/appliances. Off by default — these are cryptographically weak,
     * so enable only for a host that offers nothing modern. Applies to the whole
     * connection chain (target + jump hosts).
     */
    var allowLegacyAlgorithms: Boolean = false

    /**
     * Appearance in the session tree. [iconKey] selects one of the built-in
     * [com.haifasoft.haifassh.ui.SessionIcons] shapes (blank = default).
     * [color] is an `#RRGGBB` tint applied to the icon and name (blank = default).
     */
    var iconKey: String = ""
    var color: String = ""

    /**
     * Manual ordering among the sessions of [folderId], lowest first. Ties (and
     * the `0` every pre-ordering session loads with) fall back to name order, so
     * a tree that was never reordered stays alphabetical.
     */
    var position: Int = 0

    @get:XCollection(style = XCollection.Style.v2)
    var jumpHosts: MutableList<JumpHost> = mutableListOf()

    fun displayName(): String = name.ifBlank { "$username@$host".trim('@') }.ifBlank { "Unnamed session" }

    fun copyOf(): SshSession {
        val c = SshSession()
        c.id = id
        c.name = name
        c.folderId = folderId
        c.host = host
        c.port = port
        c.username = username
        c.authType = authType
        c.privateKeyPath = privateKeyPath
        c.protocol = protocol
        c.savePassword = savePassword
        c.allowLegacyAlgorithms = allowLegacyAlgorithms
        c.iconKey = iconKey
        c.color = color
        c.position = position
        c.jumpHosts = jumpHosts.map { it.copyOf() }.toMutableList()
        return c
    }
}
