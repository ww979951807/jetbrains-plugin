package com.haifasoft.haifassh.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores SSH secrets in the IDE's [PasswordSafe] (OS keychain / KeePass-backed),
 * keyed by session id so they never touch the serialized session XML.
 */
object SshCredentialStore {

    private const val SUBSYSTEM = "HaifaSSH"

    private fun attributes(key: String): CredentialAttributes =
        CredentialAttributesCompat.of(generateServiceName(SUBSYSTEM, key))

    private fun passwordKey(sessionId: String) = "$sessionId/password"
    private fun passphraseKey(sessionId: String) = "$sessionId/passphrase"
    private fun jumpPasswordKey(sessionId: String, index: Int) = "$sessionId/jump/$index/password"
    private fun jumpPassphraseKey(sessionId: String, index: Int) = "$sessionId/jump/$index/passphrase"

    fun getPassword(sessionId: String): String? = read(passwordKey(sessionId))
    fun setPassword(sessionId: String, value: String?) = write(passwordKey(sessionId), value)

    fun getPassphrase(sessionId: String): String? = read(passphraseKey(sessionId))
    fun setPassphrase(sessionId: String, value: String?) = write(passphraseKey(sessionId), value)

    fun getJumpPassword(sessionId: String, index: Int): String? = read(jumpPasswordKey(sessionId, index))
    fun setJumpPassword(sessionId: String, index: Int, value: String?) = write(jumpPasswordKey(sessionId, index), value)

    fun getJumpPassphrase(sessionId: String, index: Int): String? = read(jumpPassphraseKey(sessionId, index))
    fun setJumpPassphrase(sessionId: String, index: Int, value: String?) = write(jumpPassphraseKey(sessionId, index), value)

    /**
     * Upper bound on jump-host secret slots tracked per session. Used both when
     * clearing leftover slots after an edit and when wiping a deleted session, so
     * the two stay in sync and no orphaned secrets remain for any realistic chain.
     */
    const val MAX_TRACKED_JUMP_HOSTS = 64

    /** Removes every secret associated with a session (called on delete). */
    fun clearSession(sessionId: String) {
        write(passwordKey(sessionId), null)
        write(passphraseKey(sessionId), null)
        for (i in 0 until MAX_TRACKED_JUMP_HOSTS) {
            write(jumpPasswordKey(sessionId, i), null)
            write(jumpPassphraseKey(sessionId, i), null)
        }
    }

    private fun read(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))

    private fun write(key: String, value: String?) {
        PasswordSafe.instance.setPassword(attributes(key), value?.takeIf { it.isNotEmpty() })
    }
}
