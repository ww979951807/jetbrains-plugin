package com.haifasoft.haifassh.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

/**
 * A live interactive shell over SSH. Owns the PTY channel and the underlying
 * JSch session chain (target + any jump hosts) and tears them all down on [close].
 */
class SshShellHandle(
    private val channel: ChannelShell,
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val sessions: List<Session>,
) {
    val isConnected: Boolean
        get() = channel.isConnected

    fun resize(columns: Int, rows: Int) {
        if (channel.isConnected) {
            channel.setPtySize(columns, rows, 0, 0)
        }
    }

    fun exitStatus(): Int = channel.exitStatus

    /**
     * Opens an SFTP channel on the target host reusing the existing authenticated SSH session.
     */
    fun openSftpChannel(): ChannelSftp {
        require(sessions.isNotEmpty()) { "No active SSH session available" }
        val target = sessions.last()
        val sftp = target.openChannel("sftp") as ChannelSftp
        sftp.connect(30_000)
        return sftp
    }

    fun close() {
        runCatching { channel.disconnect() }
        // Disconnect in reverse order: target first, jump hosts last.
        sessions.asReversed().forEach { runCatching { it.disconnect() } }
    }
}
