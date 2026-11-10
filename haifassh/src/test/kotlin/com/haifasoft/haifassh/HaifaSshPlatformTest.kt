package com.haifasoft.haifassh

import com.haifasoft.haifassh.fs.ui.DockPosition
import com.haifasoft.haifassh.fs.ui.LocalFileExplorerPanel
import com.haifasoft.haifassh.fs.ui.LocalViewMode
import com.haifasoft.haifassh.model.SessionProtocol
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.SshSessionService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Level B IntelliJ Platform Test using BasePlatformTestCase.
 * Tests components against a real, headless IntelliJ Platform fixture (Project, Services, VFS).
 */
class HaifaSshPlatformTest : BasePlatformTestCase() {

    fun testSessionServiceCreationAndLookup() {
        val service = ApplicationManager.getApplication().getService(SshSessionService::class.java)
        assertNotNull("SshSessionService should be registered in IntelliJ application context", service)

        val folder = service.addFolder(name = "Production", parentId = "")
        assertNotNull(folder.id)
        assertEquals("Production", folder.name)

        val session = SshSession().apply {
            id = "sess-1"
            name = "Web Server"
            folderId = folder.id
            host = "192.168.1.10"
            protocol = SessionProtocol.SFTP
        }
        service.upsertSession(session)

        val found = service.findSession("sess-1")
        assertNotNull("Should find added session by ID", found)
        assertEquals("Web Server", found?.name)
        assertEquals(SessionProtocol.SFTP, found?.protocol)

        val sessionsInFolder = service.sessionsIn(folder.id)
        assertEquals(1, sessionsInFolder.size)
        assertEquals("sess-1", sessionsInFolder[0].id)
    }

    fun testLocalFileExplorerPanelNavigation() {
        val session = SshSession().apply {
            id = "test-session"
            name = "Test Session"
        }

        // Create a temporary folder with test files
        val tempDir = java.nio.file.Files.createTempDirectory("haifassh_platform_test").toFile()
        try {
            File(tempDir, "alpha.txt").writeText("Hello")
            File(tempDir, "beta.txt").writeText("World")
            File(tempDir, "nestedDir").mkdirs()

            ApplicationManager.getApplication().invokeAndWait {
                val panel = LocalFileExplorerPanel(
                    project = project,
                    session = session,
                    onUploadFiles = { /* no-op for test */ }
                )

                panel.navigateTo(tempDir)

                assertEquals("Local view mode defaults to DOCKED_PINNED", LocalViewMode.DOCKED_PINNED, panel.currentViewMode)
                assertEquals("Local dock position defaults to LEFT", DockPosition.LEFT, panel.currentPosition)

                panel.setViewMode(LocalViewMode.WINDOW)
                assertEquals(LocalViewMode.WINDOW, panel.currentViewMode)

                panel.setDockPosition(DockPosition.RIGHT)
                assertEquals(DockPosition.RIGHT, panel.currentPosition)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
