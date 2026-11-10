package com.haifasoft.haifassh.model

/**
 * A bastion / jump host that an SSH session is tunneled through (ProxyJump-style).
 * Jump hosts are applied in list order: the first entry is reached directly, each
 * subsequent hop is reached through the previous one, and the target host last.
 *
 * Mutable with defaults so the IntelliJ XML serializer can persist it.
 */
class JumpHost {
    var host: String = ""
    var port: Int = 22
    var username: String = ""
    var authType: SshAuthType = SshAuthType.PASSWORD
    var privateKeyPath: String = ""

    fun copyOf(): JumpHost {
        val c = JumpHost()
        c.host = host
        c.port = port
        c.username = username
        c.authType = authType
        c.privateKeyPath = privateKeyPath
        return c
    }

    fun label(): String = "${username.ifBlank { "?" }}@${host.ifBlank { "?" }}:$port"
}
