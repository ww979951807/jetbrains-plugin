package com.haifasoft.haifassh.terminal.assist

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import com.jediterm.terminal.ui.JediTermWidget
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.ssh.SshShellHandle
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Wraps a live terminal [widget] together with its [TerminalAssistantPanel] in a
 * draggable splitter. The panel can be docked to any side (right/left/top/bottom)
 * via the shared [HaifaSshSettings.assistantPanelPosition] preference; this pane
 * subscribes to [HaifaSshSettings.POSITION_TOPIC] and re-lays out live so every
 * open terminal moves together.
 *
 * Both terminal presentations (editor tab and tool window) mount [component] and
 * focus [preferredFocus]; callers own the SSH handle and close it separately.
 */
class SshTerminalPane(
    private val widget: JediTermWidget,
    project: Project?,
    session: SshSession,
    hostKey: String,
    parentDisposable: Disposable,
    handle: SshShellHandle? = null,
) : Disposable {

    private val splitter = OnePixelSplitter()
    private val assistant = TerminalAssistantPanel(project, session, hostKey, ::typeText, parentDisposable, handle)

    /**
     * Permanent host. The [splitter] (which always contains the live [widget]) fills the
     * center for the terminal's entire life — never reparented — so toggling the panel
     * can't blank the JediTerm canvas or break the divider drag. A reveal strip is the
     * only thing added to / removed from an edge when the panel is hidden.
     */
    private val root = JPanel(BorderLayout())
    private var reveal: JComponent? = null

    val component: JComponent get() = root
    val preferredFocus: JComponent get() = widget.terminalPanel
    val terminalWidget: JediTermWidget get() = widget

    init {
        root.add(splitter, BorderLayout.CENTER)
        relayout()
        val connection = ApplicationManager.getApplication().messageBus.connect(parentDisposable)
        connection.subscribe(HaifaSshSettings.POSITION_TOPIC, HaifaSshSettings.PositionListener {
            ApplicationManager.getApplication().invokeLater { relayout() }
        })
        connection.subscribe(HaifaSshSettings.VISIBILITY_TOPIC, HaifaSshSettings.VisibilityListener {
            ApplicationManager.getApplication().invokeLater { relayout() }
        })
    }

    /**
     * Applies the current position/visibility preference. The [widget] keeps a fixed
     * side of the splitter; only the assistant side changes (panel when shown, null
     * when hidden), which is what keeps the terminal from reparenting on toggle.
     */
    private fun relayout() {
        val settings = HaifaSshSettings.getInstance()
        val position = settings.assistantPanelPosition
        val visible = settings.assistantPanelVisible

        splitter.orientation = position == "top" || position == "bottom" // true = stacked vertically
        val assistantFirst = position == "left" || position == "top"

        // Splitter keeps one slot per component and drops whatever the slot it is
        // assigned to held before. Moving the widget to the other side while the old
        // slot still points at it therefore removes it from the splitter altogether —
        // the terminal vanishes. Vacate the stale slot first, and only when the widget
        // really is changing sides (assigning it to the slot it already occupies is a
        // no-op, which is what keeps a plain show/hide from reparenting the canvas).
        if (assistantFirst && splitter.firstComponent === widget) splitter.firstComponent = null
        if (!assistantFirst && splitter.secondComponent === widget) splitter.secondComponent = null

        if (assistantFirst) {
            splitter.secondComponent = widget
            splitter.firstComponent = if (visible) assistant else null
            splitter.proportion = ASSISTANT_FRACTION
        } else {
            splitter.firstComponent = widget
            splitter.secondComponent = if (visible) assistant else null
            splitter.proportion = 1f - ASSISTANT_FRACTION
        }

        reveal?.let { root.remove(it) }
        reveal = null
        if (!visible) {
            val strip = revealStrip(position)
            reveal = strip
            root.add(strip, edgeFor(position))
        }
        root.revalidate()
        root.repaint()
    }

    /** A thin edge bar with a single chevron that reveals the hidden assistant panel. */
    private fun revealStrip(position: String): JComponent {
        val icon = when (position) {
            "left" -> AllIcons.General.ChevronRight
            "top" -> AllIcons.General.ChevronDown
            "bottom" -> AllIcons.General.ChevronUp
            else -> AllIcons.General.ChevronLeft // right
        }
        val button = InplaceButton("Show Terminal Assistant", icon) {
            HaifaSshSettings.getInstance().assistantPanelVisible = true
        }
        val horizontal = position == "top" || position == "bottom"
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(button, if (horizontal) BorderLayout.WEST else BorderLayout.NORTH)
        }
    }

    /** BorderLayout edge the reveal strip sits on for a given dock [position]. */
    private fun edgeFor(position: String): String = when (position) {
        "left" -> BorderLayout.WEST
        "top" -> BorderLayout.NORTH
        "bottom" -> BorderLayout.SOUTH
        else -> BorderLayout.EAST // right
    }

    /** Types [text] into the live shell verbatim, without a trailing newline (never executes). */
    private fun typeText(text: String) {
        widget.ttyConnector?.write(text)
        widget.requestFocusInWindow()
    }

    override fun dispose() {
        runCatching { widget.close() }
    }

    private companion object {
        /** Share of the split given to the assistant panel. */
        const val ASSISTANT_FRACTION = 0.3f
    }
}
