package com.haifasoft.haifassh.terminal.assist

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A lightweight, searchable list of "command rows" shared by the History and
 * Commands tabs. Each row shows a primary line (and optional secondary line),
 * carries a copy-to-clipboard button, selects on single click, and types its
 * command into the shell on double click. Section-header rows group entries.
 *
 * Rows are rebuilt on demand from [setRows]; callers filter/cap beforehand, so
 * this stays a dumb renderer with no data model of its own.
 */
class CommandRowList(private val typeText: (String) -> Unit) : JBPanel<CommandRowList>(BorderLayout()) {

    /** One entry. Header rows are non-selectable group titles. */
    class Row(
        val primary: String,
        val secondary: String? = null,
        val command: String = "",
        val header: Boolean = false,
        val monospacePrimary: Boolean = false,
        val userData: Any? = null,
    )

    private val container = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = UIUtil.getListBackground()
    }

    private var selected: RowPanel? = null
    var onSelectionChanged: (() -> Unit)? = null

    /** [userData] of the currently selected row, or null. */
    val selectedUserData: Any?
        get() = selected?.row?.userData

    init {
        val scroll = JBScrollPane(container).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)
    }

    /** Replaces all rows. [moreCount] > 0 appends a muted "N more…" hint. */
    fun setRows(rows: List<Row>, moreCount: Int = 0) {
        selected = null
        onSelectionChanged?.invoke()
        container.removeAll()
        rows.forEach { container.add(RowPanel(it)) }
        if (moreCount > 0) {
            container.add(JBLabel("  …and $moreCount more; refine your search").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(6, 8)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        container.add(Box.createVerticalGlue())
        container.revalidate()
        container.repaint()
    }

    private fun select(panel: RowPanel) {
        if (panel.row.header) return
        selected?.setSelected(false)
        selected = panel
        panel.setSelected(true)
        onSelectionChanged?.invoke()
    }

    private inner class RowPanel(val row: Row) : JPanel(BorderLayout()) {
        private var isSelected = false

        init {
            isOpaque = true
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Integer.MAX_VALUE, if (row.secondary != null) JBUI.scale(44) else JBUI.scale(26))

            if (row.header) {
                border = JBUI.Borders.empty(6, 8, 2, 8)
                background = UIUtil.getListBackground()
                add(JBLabel(row.primary).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = JBUI.Fonts.smallFont().asBold()
                }, BorderLayout.WEST)
            } else {
                border = JBUI.Borders.empty(2, 8)
                background = UIUtil.getListBackground()
                add(buildText(), BorderLayout.CENTER)
                add(buildActions(), BorderLayout.EAST)
                installMouse()
                toolTipText = "Double-click to type into the terminal (not executed)"
            }
        }

        private fun buildText(): JComponent {
            val primaryLabel = JBLabel(row.primary).apply {
                if (row.monospacePrimary) font = JBUI.Fonts.create("Monospaced", font.size)
            }
            if (row.secondary == null) return primaryLabel
            val text = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            primaryLabel.font = primaryLabel.font.deriveFont(java.awt.Font.BOLD)
            primaryLabel.alignmentX = Component.LEFT_ALIGNMENT
            val secondaryLabel = JBLabel(row.secondary).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBUI.Fonts.create("Monospaced", JBUI.Fonts.smallFont().size)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            text.add(primaryLabel)
            text.add(secondaryLabel)
            return text
        }

        private fun buildActions(): JComponent {
            val copy = InplaceButton("Copy command", AllIcons.Actions.Copy) {
                CopyPasteManager.getInstance().setContents(StringSelection(row.command))
            }
            return JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(copy)
            }
        }

        private fun installMouse() {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = select(this@RowPanel)
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && row.command.isNotEmpty()) typeText(row.command)
                }
            })
        }

        fun setSelected(value: Boolean) {
            isSelected = value
            background = if (value) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val fg = if (value) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            UIUtil.forEachComponentInHierarchy(this) {
                if (it is JBLabel && it.foreground != UIUtil.getContextHelpForeground()) it.foreground = fg
            }
            repaint()
        }
    }
}
