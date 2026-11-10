package com.haifasoft.haifassh.terminal.editor

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.HierarchyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * What a single editor tab shows for one terminal.
 *
 * A live terminal is one Swing component, and Swing gives a component exactly one
 * parent. Splitting the editor opens the same file in a second editor window, so
 * the platform builds a second [SshTerminalFileEditor] for it — and if both editors
 * handed out the same component, adding it to the new split would silently rip it
 * out of the old one. That split is then left with an empty container: nothing
 * renders in it, and dragging the divider between the two splits appears to do
 * nothing because there is nothing on that side to resize.
 *
 * Each editor therefore gets its own view, and [SshTerminalVirtualFile] tracks
 * which one currently holds the terminal — the split the user last brought into
 * view. The others show a placeholder offering to move it over.
 */
class SshTerminalEditorView(
    private val file: SshTerminalVirtualFile,
    private val terminal: JComponent,
) : JPanel(BorderLayout()) {

    private val placeholder: JComponent = buildPlaceholder()

    /** True while this split is the one showing the live terminal. */
    val holdsTerminal: Boolean get() = terminal.parent === this

    /** The terminal when this split has it; otherwise this view, which has nothing to focus. */
    val preferredFocus: JComponent get() = if (holdsTerminal) terminal else this

    init {
        showPlaceholder()
        // Claim the terminal whenever this split comes on screen — the tab being opened,
        // selected, or revealed because the split holding it was closed. When two splits
        // show the same terminal the most recent one wins, which is the one the user just
        // acted on; the other offers the "Show it here" link to take it back.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                claim()
            }
        }
    }

    /** Asks for the live terminal, taking it from whichever split currently has it. */
    fun claim() = file.showTerminalIn(this)

    /** Called by [SshTerminalVirtualFile] when this view wins the terminal. */
    fun showTerminal() {
        if (holdsTerminal) return
        removeAll()
        add(terminal, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /** Called by [SshTerminalVirtualFile] when another split takes the terminal. */
    fun showPlaceholder() {
        removeAll()
        add(placeholder, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /** Centered "the terminal lives in the other split" notice, with a link to move it here. */
    private fun buildPlaceholder(): JComponent {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(
                JBLabel("This terminal is showing in another editor split.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = Component.CENTER_ALIGNMENT
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(ActionLink("Show it here") { claim() }.apply { alignmentX = Component.CENTER_ALIGNMENT })
        }
        // Default constraints center the single child in the available space.
        return JPanel(GridBagLayout()).apply { add(column, GridBagConstraints()) }
    }
}
