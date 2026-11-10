package com.haifasoft.haifassh.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandRecorderTest {

    @Test
    fun testRecognisesSecretPrompts() {
        listOf(
            "[sudo] password for adel: ",
            "Password:",
            "Enter passphrase for key '/home/adel/.ssh/id_ed25519': ",
            "Enter PIN for 'PIV Card':",
            "Verification code: ",
            "\u001B[1;31mPassword:\u001B[0m ",
        ).forEach { assertTrue(it, TerminalCommandRecorder.isSecretPrompt(it)) }
    }

    @Test
    fun testIgnoresOrdinaryPrompts() {
        listOf(
            "adel@host:~$ ",
            "adel@host:~$ ls -la",
            "adel@host:~$ grep password config.yml",
            "mysql> ",
            "",
        ).forEach { assertFalse(it, TerminalCommandRecorder.isSecretPrompt(it)) }
    }
}
