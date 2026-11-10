package com.haifasoft.haifassh.terminal

import com.haifasoft.haifassh.settings.SshCommandHistoryService

/**
 * Reconstructs the command lines a user types into a shell by observing the raw
 * keystrokes flowing out through [SshTtyConnector], and records each completed
 * line (on Enter) into [SshCommandHistoryService] under [hostKey].
 *
 * This is a deliberate heuristic on the outgoing byte stream — it has no
 * knowledge of the remote shell's line editor. It handles plain typing,
 * backspace and a few common line-kill controls, but by design it does NOT
 * faithfully capture:
 *  - history recall via the arrow keys (the recalled text never crosses this
 *    stream, so an up-arrow + Enter records nothing),
 *  - tab-completion (the completion is chosen remotely),
 *  - multi-line constructs such as heredocs or continued lines.
 * Any escape sequence (cursor keys, query responses JediTerm writes back, etc.)
 * resets the in-progress line rather than corrupting it.
 *
 * Secrets: a line typed while the remote's current output line looks like a
 * secret prompt (`[sudo] password for …:`, `Enter passphrase …:`, `PIN:`) is
 * dropped, since those keystrokes are a password rather than a command. This is
 * a heuristic too — a silent prompt it doesn't recognise can still be recorded.
 *
 * All state is guarded by the instance monitor; JediTerm calls write() off the
 * EDT on its own thread.
 */
class TerminalCommandRecorder(private val hostKey: String) {

    private val history = SshCommandHistoryService.getInstance()
    private val currentLine = StringBuilder()

    /** The remote's current (unterminated) output line, capped to [MAX_TAIL] chars. */
    private val incomingTail = StringBuilder()

    /** Feeds one chunk of text received from the remote shell. */
    @Synchronized
    fun onIncoming(text: CharSequence) {
        for (c in text) {
            if (c == '\r' || c == '\n') incomingTail.setLength(0) else incomingTail.append(c)
        }
        if (incomingTail.length > MAX_TAIL) incomingTail.delete(0, incomingTail.length - MAX_TAIL)
    }

    /** Feeds one chunk of outgoing keystroke text (already decoded to a String). */
    @Synchronized
    fun onOutgoing(text: String) {
        for (c in text) {
            when (c) {
                '\r', '\n' -> commit()
                '\b', DEL -> if (currentLine.isNotEmpty()) currentLine.deleteCharAt(currentLine.length - 1)
                CTRL_C, CTRL_U, CTRL_W, ESC -> currentLine.setLength(0)
                else -> if (!c.isISOControl()) currentLine.append(c)
            }
        }
    }

    private fun commit() {
        val line = currentLine.toString()
        currentLine.setLength(0)
        if (line.isBlank() || isSecretPrompt(incomingTail)) return
        history.record(hostKey, line)
    }

    companion object {
        private const val MAX_TAIL = 512
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
        private val SECRET_PROMPT = Regex(
            "\\b(password|passphrase|passcode|pin|otp|one-time code|verification code)\\b.*[:?]\\s*$",
            RegexOption.IGNORE_CASE,
        )

        /** True if [promptLine] (raw terminal output, escapes allowed) ends in a secret prompt. */
        fun isSecretPrompt(promptLine: CharSequence): Boolean =
            SECRET_PROMPT.containsMatchIn(ANSI_ESCAPE.replace(promptLine, ""))

        private const val ESC = '\u001B'
        private const val DEL = '\u007F'
        private const val CTRL_C = '\u0003'
        private const val CTRL_U = '\u0015'
        private const val CTRL_W = '\u0017'
    }
}
