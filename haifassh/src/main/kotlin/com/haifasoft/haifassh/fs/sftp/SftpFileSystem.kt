package com.haifasoft.haifassh.fs.sftp

import com.haifasoft.haifassh.fs.RemoteFile
import com.haifasoft.haifassh.fs.RemoteFileSystem
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Vector

class SftpFileSystem(
    private val channel: ChannelSftp,
    private val sessions: List<Session> = emptyList(),
    private val closeSessionsOnDisconnect: Boolean = true,
) : RemoteFileSystem {

    override val isConnected: Boolean
        get() = channel.isConnected

    override fun getHomeDirectory(): String {
        return runCatching { channel.home }.getOrNull()?.ifBlank { "/" } ?: "/"
    }

    @Suppress("UNCHECKED_CAST")
    override fun listFiles(remotePath: String): List<RemoteFile> {
        val targetPath = if (remotePath.isBlank()) "." else remotePath
        val entries = channel.ls(targetPath) as? Vector<ChannelSftp.LsEntry> ?: Vector()
        val result = mutableListOf<RemoteFile>()
        for (entry in entries) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val attrs = entry.attrs
            val fullPath = normalizePath(targetPath, name)
            result.add(
                RemoteFile(
                    name = name,
                    path = fullPath,
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                    permissions = attrs.permissionsString,
                    lastModified = attrs.mTime.toLong() * 1000L,
                    isLink = attrs.isLink,
                )
            )
        }
        return result
    }

    override fun upload(localFile: File, remoteDir: String, onProgress: (bytes: Long, total: Long) -> Unit) {
        val remotePath = normalizePath(remoteDir, localFile.name)
        val fileSize = localFile.length()
        val monitor = object : SftpProgressMonitor {
            private var transferred = 0L
            override fun init(op: Int, src: String?, dest: String?, max: Long) {
                transferred = 0L
            }

            override fun count(count: Long): Boolean {
                transferred += count
                onProgress(transferred, fileSize)
                return true
            }

            override fun end() {
                onProgress(fileSize, fileSize)
            }
        }

        FileInputStream(localFile).use { input ->
            channel.put(input, remotePath, monitor, ChannelSftp.OVERWRITE)
        }
    }

    override fun download(remotePath: String, localTarget: File, onProgress: (bytes: Long, total: Long) -> Unit) {
        val fileSize = runCatching { channel.stat(remotePath).size }.getOrDefault(-1L)
        val monitor = object : SftpProgressMonitor {
            private var transferred = 0L
            override fun init(op: Int, src: String?, dest: String?, max: Long) {
                transferred = 0L
            }

            override fun count(count: Long): Boolean {
                transferred += count
                onProgress(transferred, fileSize)
                return true
            }

            override fun end() {
                if (fileSize > 0) onProgress(fileSize, fileSize)
            }
        }

        FileOutputStream(localTarget).use { output ->
            channel.get(remotePath, output, monitor)
        }
    }

    override fun delete(remotePath: String, isDirectory: Boolean) {
        if (isDirectory) {
            channel.rmdir(remotePath)
        } else {
            channel.rm(remotePath)
        }
    }

    override fun createDirectory(remotePath: String) {
        channel.mkdir(remotePath)
    }

    override fun rename(oldPath: String, newPath: String) {
        channel.rename(oldPath, newPath)
    }

    override fun disconnect() {
        runCatching { channel.disconnect() }
        if (closeSessionsOnDisconnect) {
            sessions.asReversed().forEach { runCatching { it.disconnect() } }
        }
    }

    private fun normalizePath(dir: String, name: String): String {
        val cleanDir = dir.trimEnd('/')
        return if (cleanDir.isEmpty()) "/$name" else "$cleanDir/$name"
    }
}
