package com.haifasoft.haifassh.fs.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.model.SshSession
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.WindowConstants

/**
 * Modeless standalone floating window hosting [LocalFileExplorerPanel]
 * when View Mode is set to Window, Float, or Undock.
 */
class LocalFileExplorerWindow(
    project: Project,
    session: SshSession,
    val panel: LocalFileExplorerPanel,
    private val onWindowClosed: () -> Unit,
) : JDialog(
    WindowManager.getInstance().suggestParentWindow(project),
    "Local Files — ${session.displayName()}",
    ModalityType.MODELESS,
) {

    init {
        isModal = false
        defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        setSize(JBUI.scale(760), JBUI.scale(520))
        minimumSize = Dimension(JBUI.scale(480), JBUI.scale(320))
        setLocationRelativeTo(parent)

        contentPane.layout = BorderLayout()
        attachPanel()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                onWindowClosed()
            }
        })
    }

    fun attachPanel() {
        contentPane.removeAll()
        contentPane.add(panel, BorderLayout.CENTER)
        contentPane.revalidate()
        contentPane.repaint()
    }

    fun detachPanel() {
        contentPane.removeAll()
        contentPane.revalidate()
        contentPane.repaint()
    }
}
