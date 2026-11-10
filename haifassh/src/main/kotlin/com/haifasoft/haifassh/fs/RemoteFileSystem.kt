package com.haifasoft.haifassh.fs

import java.io.File

/**
 * Common file operations abstraction for both SFTP and FTP.
 */
interface RemoteFileSystem : AutoCloseable {
    val isConnected: Boolean

    fun getHomeDirectory(): String

    fun listFiles(remotePath: String): List<RemoteFile>

    fun upload(localFile: File, remoteDir: String, onProgress: (bytes: Long, total: Long) -> Unit)

    fun download(remotePath: String, localTarget: File, onProgress: (bytes: Long, total: Long) -> Unit)

    fun delete(remotePath: String, isDirectory: Boolean)

    fun createDirectory(remotePath: String)

    fun rename(oldPath: String, newPath: String)

    fun disconnect()

    override fun close() = disconnect()
}
