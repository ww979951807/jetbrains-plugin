package com.haifasoft.haifassh.ssh

import com.haifasoft.haifassh.model.SshAuthType

/**
 * A single, fully-resolved hop in an SSH connection chain (secrets already
 * fetched from PasswordSafe). The last hop in the list is the target host;
 * any preceding hops are jump hosts.
 *
 * Secrets are held as [ByteArray] (not [String]) so the connector can zero them
 * after use ([SshConnector] wipes them once the chain is established). The bytes
 * are UTF-8 encodings of the password / key passphrase.
 */
data class HopConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val privateKeyPath: String,
    val password: ByteArray?,
    val passphrase: ByteArray?,
    /** Opt-in fallback to legacy SHA-1/DSA algorithms for this hop. */
    val allowLegacy: Boolean = false,
)
