package com.haifasoft.haifassh.terminal.assist

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import java.awt.BorderLayout
import javax.swing.JPanel

import com.haifasoft.haifassh.ssh.SshShellHandle

/**
 * The companion panel docked next to a terminal: a small header (title + a gear
 * that moves the panel to any side) over a two-tab pane — History and Commands.
 * Positioning is a shared preference on [HaifaSshSettings]; the enclosing
 * [SshTerminalPane] listens for changes and re-lays out the splitter.
 */
class TerminalAssistantPanel(
    project: Project?,
    session: SshSession,
    hostKey: String,
    typeText: (String) -> Unit,
    parentDisposable: Disposable,
    handle: SshShellHandle? = null,
) : JBPanel<TerminalAssistantPanel>(BorderLayout()) {

    init {
        add(buildHeader(), BorderLayout.NORTH)

        val tabs = JBTabbedPane().apply {
            if (project != null) {
                addTab("SFTP Files", AllIcons.Nodes.Folder, SftpTab(project, session, parentDisposable, handle, typeText))
            }
            addTab("History", AllIcons.Vcs.History, CommandHistoryTab(hostKey, typeText, parentDisposable))
            addTab("Commands", CommandSnippetsTab(project, session.id, session.displayName(), typeText, parentDisposable))
        }
        add(tabs, BorderLayout.CENTER)
    }

    private fun buildHeader(): JPanel {
        // InplaceButton hands the ActionListener an ActionEvent whose source is the
        // originating MouseEvent, not the button — so capture the button ourselves
        // rather than casting the event source (which crashes with a ClassCastException).
        var move: InplaceButton? = null
        move = InplaceButton("Move panel", AllIcons.General.LayoutEditorPreview) {
            move?.let(::showPositionMenu)
        }
        val hide = InplaceButton("Hide panel", AllIcons.Actions.Close) {
            HaifaSshSettings.getInstance().assistantPanelVisible = false
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(3, 8),
            )
            add(JBLabel("Terminal Assistant").apply { font = JBUI.Fonts.smallFont().asBold() }, BorderLayout.WEST)
            val actions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(move)
                add(hide)
            }
            add(actions, BorderLayout.EAST)
        }
    }

    private fun showPositionMenu(anchor: InplaceButton) {
        val settings = HaifaSshSettings.getInstance()
        val current = settings.assistantPanelPosition
        val group = DefaultActionGroup()
        POSITIONS.forEach { (value, label) ->
            group.add(object : DumbAwareToggleAction(label) {
                override fun isSelected(e: AnActionEvent) = value == current
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    settings.assistantPanelPosition = value
                }
            })
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, SimpleDataContext.builder().build(),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .show(RelativePoint(anchor, java.awt.Point(0, anchor.height)))
    }

    private companion object {
        val POSITIONS = listOf(
            "right" to "Dock Right",
            "left" to "Dock Left",
            "bottom" to "Dock Bottom",
            "top" to "Dock Top",
        )
    }
}
