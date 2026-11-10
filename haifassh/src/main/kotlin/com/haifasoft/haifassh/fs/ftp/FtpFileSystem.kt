package com.haifasoft.haifassh.fs.ftp

import com.haifasoft.haifassh.fs.RemoteFile
import com.haifasoft.haifassh.fs.RemoteFileSystem
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FtpFileSystem(
    private val client: FTPClient,
) : RemoteFileSystem {

    override val isConnected: Boolean
        get() = client.isConnected

    override fun getHomeDirectory(): String {
        return client.printWorkingDirectory() ?: "/"
    }

    override fun listFiles(remotePath: String): List<RemoteFile> {
        val targetPath = if (remotePath.isBlank()) "." else remotePath
        val files = client.listFiles(targetPath) ?: emptyArray()
        val result = mutableListOf<RemoteFile>()
        for (f in files) {
            val name = f.name
            if (name == "." || name == "..") continue
            val fullPath = normalizePath(targetPath, name)
            val perm = formatFtpPermissions(f)
            val time = f.timestamp?.timeInMillis ?: 0L
            result.add(
                RemoteFile(
                    name = name,
                    path = fullPath,
                    isDirectory = f.isDirectory,
                    size = f.size,
                    permissions = perm,
                    lastModified = time,
                    isLink = f.isSymbolicLink,
                )
            )
        }
        return result
    }

    override fun upload(localFile: File, remoteDir: String, onProgress: (bytes: Long, total: Long) -> Unit) {
        val remotePath = normalizePath(remoteDir, localFile.name)
        val total = localFile.length()
        var transferred = 0L
        FileInputStream(localFile).use { input ->
            val wrapped = object : InputStream() {
                override fun read(): Int {
                    val b = input.read()
                    if (b != -1) {
                        transferred++
                        onProgress(transferred, total)
                    }
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = input.read(b, off, len)
                    if (read > 0) {
                        transferred += read
                        onProgress(transferred, total)
                    }
                    return read
                }
            }
            if (!client.storeFile(remotePath, wrapped)) {
                throw IOException("Failed to upload file to $remotePath: ${client.replyString}")
            }
        }
    }

    override fun download(remotePath: String, localTarget: File, onProgress: (bytes: Long, total: Long) -> Unit) {
        // Try getting size for progress
        val files = client.listFiles(remotePath)
        val total = files?.firstOrNull()?.size ?: -1L
        var transferred = 0L
        FileOutputStream(localTarget).use { output ->
            val wrapped = object : OutputStream() {
                override fun write(b: Int) {
                    output.write(b)
                    transferred++
                    onProgress(transferred, total)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    output.write(b, off, len)
                    transferred += len
                    onProgress(transferred, total)
                }
            }
            if (!client.retrieveFile(remotePath, wrapped)) {
                throw IOException("Failed to download file from $remotePath: ${client.replyString}")
            }
        }
    }

    override fun delete(remotePath: String, isDirectory: Boolean) {
        val success = if (isDirectory) {
            client.removeDirectory(remotePath)
        } else {
            client.deleteFile(remotePath)
        }
        if (!success) {
            throw IOException("Failed to delete $remotePath: ${client.replyString}")
        }
    }

    override fun createDirectory(remotePath: String) {
        if (!client.makeDirectory(remotePath)) {
            throw IOException("Failed to create directory $remotePath: ${client.replyString}")
        }
    }

    override fun rename(oldPath: String, newPath: String) {
        if (!client.rename(oldPath, newPath)) {
            throw IOException("Failed to rename from $oldPath to $newPath: ${client.replyString}")
        }
    }

    override fun disconnect() {
        runCatching {
            if (client.isConnected) {
                client.logout()
                client.disconnect()
            }
        }
    }

    private fun normalizePath(dir: String, name: String): String {
        val cleanDir = dir.trimEnd('/')
        return if (cleanDir.isEmpty()) "/$name" else "$cleanDir/$name"
    }

    private fun formatFtpPermissions(f: FTPFile): String {
        val type = if (f.isDirectory) "d" else if (f.isSymbolicLink) "l" else "-"
        fun perm(access: Int): String {
            val r = if (f.hasPermission(access, FTPFile.READ_PERMISSION)) "r" else "-"
            val w = if (f.hasPermission(access, FTPFile.WRITE_PERMISSION)) "w" else "-"
            val x = if (f.hasPermission(access, FTPFile.EXECUTE_PERMISSION)) "x" else "-"
            return "$r$w$x"
        }
        return type + perm(FTPFile.USER_ACCESS) + perm(FTPFile.GROUP_ACCESS) + perm(FTPFile.WORLD_ACCESS)
    }

    companion object {
        fun connect(host: String, port: Int, username: String, password: String?): FtpFileSystem {
            val client = FTPClient()
            client.defaultTimeout = 30_000
            client.connectTimeout = 30_000
            client.setDataTimeout(java.time.Duration.ofSeconds(30))
            client.connect(host, port)
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                throw IOException("FTP server refused connection: $reply")
            }
            client.controlEncoding = "UTF-8"
            val loggedIn = client.login(username.ifBlank { "anonymous" }, password ?: "")
            if (!loggedIn) {
                client.disconnect()
                throw IOException("FTP authentication failed for user $username: ${client.replyString}")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            return FtpFileSystem(client)
        }
    }
}
