package com.haifasoft.haifassh.fs

import java.io.File
import java.io.IOException

/**
 * Metadata for a remote file or directory in SFTP or FTP.
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val lastModified: Long,
    val isLink: Boolean = false,
) {
    fun formattedSize(): String {
        if (isDirectory) return "-"
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.1f GB", gb)
    }

    /**
     * The local file `dir/name` to download this file into. [name] comes straight
     * from the server's directory listing, so a hostile server could send
     * `../../.bashrc` or an absolute path; anything other than a single plain path
     * segment is refused rather than written outside [dir].
     */
    fun localTargetIn(dir: File): File {
        if (!isSafeLocalName(name)) throw IOException("Refusing to save remote file with unsafe name \"$name\"")
        return File(dir, name)
    }

    companion object {
        fun isSafeLocalName(name: String): Boolean {
            if (name.isEmpty() || name == "." || name == "..") return false
            if (name.any { it == '/' || it == '\\' || it == '\u0000' }) return false
            // On Windows ':' selects a drive or an alternate data stream.
            if (File.separatorChar == '\\' && ':' in name) return false
            return true
        }
    }
}
