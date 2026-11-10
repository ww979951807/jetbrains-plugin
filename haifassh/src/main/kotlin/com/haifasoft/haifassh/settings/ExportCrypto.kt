package com.haifasoft.haifassh.settings

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for export bundles that carry secrets, so an exported
 * file is never plaintext passwords on disk.
 *
 * On-disk format (UTF-8 text):
 * ```
 * HAIFASSH-ENC-V1
 * <base64( salt[16] | iv[12] | AES-GCM ciphertext+tag )>
 * ```
 * The AES-256 key is derived from the user's passphrase with PBKDF2-HMAC-SHA256.
 * Decryption fails (GCM tag mismatch) on a wrong passphrase or any tampering.
 */
object ExportCrypto {

    const val MAGIC = "HAIFASSH-ENC-V1"

    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 210_000 // OWASP-recommended floor for PBKDF2-HMAC-SHA256

    /** True if [text] is an encrypted HaifaSSH bundle (vs. a plain config-only XML). */
    fun isEncrypted(text: String): Boolean = text.startsWith(MAGIC)

    fun encrypt(plaintext: String, passphrase: CharArray): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = salt + iv + ciphertext
        return MAGIC + "\n" + Base64.getEncoder().encodeToString(blob)
    }

    /** @throws javax.crypto.AEADBadTagException on a wrong passphrase or tampering. */
    fun decrypt(text: String, passphrase: CharArray): String {
        require(isEncrypted(text)) { "Not an encrypted HaifaSSH bundle" }
        val blob = Base64.getDecoder().decode(text.substringAfter('\n').trim())
        val salt = blob.copyOfRange(0, SALT_LEN)
        val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ciphertext = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
