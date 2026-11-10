package com.haifasoft.haifassh.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.haifasoft.haifassh.ssh.SshShellHandle
import java.io.InputStreamReader
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Bridges a JediTerm widget to a live SSH shell: JediTerm reads characters and
 * writes keystrokes through this connector, which proxies to the SSH PTY channel.
 */
class SshTtyConnector(
    private val name: String,
    private val handle: SshShellHandle,
    private val recorder: TerminalCommandRecorder? = null,
) : TtyConnector {

    private val reader = InputStreamReader(handle.inputStream, StandardCharsets.UTF_8)
    private val output = handle.outputStream

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val n = reader.read(buf, offset, length)
        // Lets the recorder see the remote's prompts, so lines typed at a password prompt aren't recorded.
        if (n > 0) recorder?.onIncoming(CharBuffer.wrap(buf, offset, n))
        return n
    }

    override fun write(bytes: ByteArray) {
        // Single choke point for outgoing keystrokes; write(String) delegates here,
        // so the recorder is fed exactly once regardless of which overload is used.
        recorder?.onOutgoing(String(bytes, StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    override fun write(string: String) = write(string.toByteArray(StandardCharsets.UTF_8))

    override fun isConnected(): Boolean = handle.isConnected

    override fun resize(termSize: TermSize) = handle.resize(termSize.columns, termSize.rows)

    override fun waitFor(): Int {
        while (handle.isConnected) {
            Thread.sleep(100)
        }
        return handle.exitStatus()
    }

    override fun ready(): Boolean = reader.ready()

    override fun getName(): String = name

    override fun close() = handle.close()
}
