package com.haifasoft.haifassh.fs

import com.haifasoft.haifassh.model.SessionProtocol
import com.haifasoft.haifassh.model.SshSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class RemoteFileTest {

    @Test
    fun testFormattedSize() {
        val dir = RemoteFile("docs", "/home/user/docs", isDirectory = true, size = 4096, permissions = "rwxr-xr-x", lastModified = 0L)
        assertEquals("-", dir.formattedSize())

        val smallFile = RemoteFile("test.txt", "/home/user/test.txt", isDirectory = false, size = 500, permissions = "rw-r--r--", lastModified = 0L)
        assertEquals("500 B", smallFile.formattedSize())

        val kbFile = RemoteFile("doc.pdf", "/home/user/doc.pdf", isDirectory = false, size = 2048, permissions = "rw-r--r--", lastModified = 0L)
        assertEquals("2.0 KB", kbFile.formattedSize())

        val mbFile = RemoteFile("video.mp4", "/home/user/video.mp4", isDirectory = false, size = 5 * 1024 * 1024, permissions = "rw-r--r--", lastModified = 0L)
        assertEquals("5.0 MB", mbFile.formattedSize())
    }

    @Test
    fun testSessionProtocolCopy() {
        val session = SshSession().apply {
            name = "Test FTP"
            host = "ftp.example.com"
            protocol = SessionProtocol.FTP
            port = 21
        }
        val copy = session.copyOf()
        assertEquals(SessionProtocol.FTP, copy.protocol)
        assertEquals(21, copy.port)
        assertEquals("Test FTP", copy.name)

        val sftpSession = SshSession().apply {
            name = "Test SFTP"
            protocol = SessionProtocol.SFTP
            port = 22
        }
        val sftpCopy = sftpSession.copyOf()
        assertEquals(SessionProtocol.SFTP, sftpCopy.protocol)
    }

    @Test
    fun testGigabyteFormattedSize() {
        val largeFile = RemoteFile("disk.iso", "/data/disk.iso", isDirectory = false, size = 3L * 1024 * 1024 * 1024, permissions = "r--------", lastModified = 0L)
        assertEquals("3.0 GB", largeFile.formattedSize())
    }

    @Test
    fun testLocalTargetRejectsServerSuppliedTraversal() {
        val dir = File("downloads")
        for (name in listOf("../.bashrc", "../../.ssh/authorized_keys", "/etc/passwd", "a\\..\\b", "..", ".", "", "a\u0000b")) {
            val file = RemoteFile(name, "/srv/$name", isDirectory = false, size = 1, permissions = "rw-r--r--", lastModified = 0L)
            assertThrows(IOException::class.java) { file.localTargetIn(dir) }
        }
    }

    @Test
    fun testLocalTargetAcceptsPlainNames() {
        val dir = File("downloads")
        for (name in listOf("report.txt", ".env", "..hidden", "with space.log")) {
            val file = RemoteFile(name, "/srv/$name", isDirectory = false, size = 1, permissions = "rw-r--r--", lastModified = 0L)
            assertEquals(File(dir, name), file.localTargetIn(dir))
        }
    }
}
