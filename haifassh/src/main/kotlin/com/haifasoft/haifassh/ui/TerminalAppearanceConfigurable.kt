package com.haifasoft.haifassh.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.terminal.theme.TerminalColorScheme
import com.haifasoft.haifassh.terminal.theme.TerminalSchemes
import com.haifasoft.haifassh.terminal.theme.toAwtColor
import com.haifasoft.haifassh.terminal.theme.toRgbInt
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page (Tools → HaifaSSH Terminal) to choose the terminal color scheme:
 * a ready-made template, "Follow IDE Theme", or a fully editable "Custom" scheme.
 * "Copy to Custom" seeds the editable colors from the previewed template so a
 * template can be used as a starting point for customization.
 */
class TerminalAppearanceConfigurable : Configurable {

    private val settings = HaifaSshSettings.getInstance()

    private val schemeCombo = ComboBox(TerminalSchemes.names().toTypedArray())
    private val bufferLinesSpinner = JBIntSpinner(
        5000,
        HaifaSshSettings.MIN_BUFFER_LINES,
        HaifaSshSettings.MAX_BUFFER_LINES,
        500,
    )
    private val preview = SchemePreview()

    private val background = ColorPanel()
    private val foreground = ColorPanel()
    private val selectionBackground = ColorPanel()
    private val selectionForeground = ColorPanel()
    private val cursor = ColorPanel()
    private val ansiPanels = Array(16) { ColorPanel() }
    private val copyToCustomButton = JButton("Copy to Custom")

    private var root: JComponent? = null

    override fun getDisplayName(): String = "HaifaSSH Terminal"

    override fun createComponent(): JComponent {
        schemeCombo.addActionListener {
            onSchemeSelected(selectedName())
        }
        copyToCustomButton.addActionListener {
            // Seed the Custom editors from whatever is currently shown, then switch to Custom.
            val current = readUiScheme()
            schemeCombo.selectedItem = TerminalSchemes.CUSTOM
            applySchemeToEditors(current)
            setEditorsEnabled(true)
            preview.scheme = readUiScheme()
            preview.repaint()
        }

        val colorEditors = arrayOf(
            background, foreground, selectionBackground, selectionForeground, cursor, *ansiPanels,
        )
        colorEditors.forEach { panel ->
            panel.addActionListener {
                preview.scheme = readUiScheme()
                preview.repaint()
            }
        }

        val ansiGrid = JPanel(GridLayout(2, 8, 4, 4)).apply {
            ansiPanels.forEach { add(it) }
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Scrollback (lines):", bufferLinesSpinner)
            .addComponent(
                JBLabel("Per terminal; applies to newly opened terminals. Higher keeps more history but uses more memory.")
                    .apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND },
            )
            .addSeparator()
            .addLabeledComponent("Color scheme:", schemeCombo)
            .addComponent(copyToCustomButton)
            .addSeparator()
            .addLabeledComponent("Background:", background)
            .addLabeledComponent("Foreground:", foreground)
            .addLabeledComponent("Selection background:", selectionBackground)
            .addLabeledComponent("Selection foreground:", selectionForeground)
            .addLabeledComponent("Cursor:", cursor)
            .addLabeledComponent("ANSI palette (0–7 normal, 8–15 bright):", ansiGrid)
            .addComponent(JBLabel("Preview:").apply { border = JBUI.Borders.emptyTop(8) })
            .addComponent(preview)
            .panel

        root = form
        reset()
        return form
    }

    private fun selectedName(): String = schemeCombo.selectedItem as? String ?: TerminalSchemes.FOLLOW_IDE

    private fun onSchemeSelected(name: String) {
        val scheme = if (name == TerminalSchemes.CUSTOM) settings.customScheme() else TerminalSchemes.byName(name)
        applySchemeToEditors(scheme)
        setEditorsEnabled(name == TerminalSchemes.CUSTOM)
        preview.scheme = scheme
        preview.repaint()
    }

    private fun applySchemeToEditors(scheme: TerminalColorScheme) {
        background.selectedColor = scheme.background.toAwtColor()
        foreground.selectedColor = scheme.foreground.toAwtColor()
        selectionBackground.selectedColor = scheme.selectionBackground.toAwtColor()
        selectionForeground.selectedColor = scheme.selectionForeground.toAwtColor()
        cursor.selectedColor = scheme.cursor.toAwtColor()
        ansiPanels.forEachIndexed { i, p -> p.selectedColor = scheme.ansi[i].toAwtColor() }
    }

    private fun setEditorsEnabled(enabled: Boolean) {
        background.isEnabled = enabled
        foreground.isEnabled = enabled
        selectionBackground.isEnabled = enabled
        selectionForeground.isEnabled = enabled
        cursor.isEnabled = enabled
        ansiPanels.forEach { it.isEnabled = enabled }
    }

    /** Builds a scheme from the current editor values (used for preview and saving Custom). */
    private fun readUiScheme(): TerminalColorScheme = TerminalColorScheme(
        name = TerminalSchemes.CUSTOM,
        background = rgb(background, 0x1E1E1E),
        foreground = rgb(foreground, 0xCCCCCC),
        selectionBackground = rgb(selectionBackground, 0x264F78),
        selectionForeground = rgb(selectionForeground, 0xFFFFFF),
        cursor = rgb(cursor, 0xCCCCCC),
        ansi = IntArray(16) { rgb(ansiPanels[it], 0x000000) },
    )

    private fun rgb(panel: ColorPanel, fallback: Int): Int = panel.selectedColor?.toRgbInt() ?: fallback

    override fun isModified(): Boolean {
        if (bufferLinesSpinner.number != settings.terminalBufferMaxLines) return true
        if (selectedName() != settings.terminalSchemeName) return true
        if (selectedName() == TerminalSchemes.CUSTOM) {
            return !sameColors(readUiScheme(), settings.customScheme())
        }
        return false
    }

    override fun apply() {
        settings.terminalBufferMaxLines = bufferLinesSpinner.number
        settings.terminalSchemeName = selectedName()
        if (selectedName() == TerminalSchemes.CUSTOM) {
            settings.setCustomScheme(readUiScheme())
        }
        settings.fireSchemeChanged()
    }

    override fun reset() {
        bufferLinesSpinner.value = settings.terminalBufferMaxLines
        schemeCombo.selectedItem = settings.terminalSchemeName
        onSchemeSelected(settings.terminalSchemeName)
    }

    private fun sameColors(a: TerminalColorScheme, b: TerminalColorScheme): Boolean =
        a.background == b.background && a.foreground == b.foreground &&
            a.selectionBackground == b.selectionBackground && a.selectionForeground == b.selectionForeground &&
            a.cursor == b.cursor && a.ansi.contentEquals(b.ansi)

    /** Small swatch that paints a representative terminal line using the candidate scheme. */
    private class SchemePreview : JBPanel<SchemePreview>() {
        var scheme: TerminalColorScheme = TerminalSchemes.fromIde()

        init {
            preferredSize = Dimension(520, 96)
        }

        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val s = scheme
            g.color = s.background.toAwtColor()
            g.fillRect(0, 0, width, height)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            val fm = g.fontMetrics
            val lh = fm.height
            var y = lh

            g.color = s.foreground.toAwtColor()
            g.drawString("user@host:~\$ ls -al", 8, y)
            y += lh

            // A row of the 8 normal ANSI colors as colored tokens.
            var x = 8
            val tokens = listOf("dir", "exe", "link", "img", "warn", "err", "ok", "txt")
            for (i in 0 until 8) {
                g.color = s.ansi[i].toAwtColor()
                g.drawString(tokens[i], x, y)
                x += fm.stringWidth(tokens[i]) + 12
            }
            y += lh

            // Selection sample.
            val sel = "selected text"
            g.color = s.selectionBackground.toAwtColor()
            g.fillRect(8, y - fm.ascent, fm.stringWidth(sel) + 4, lh)
            g.color = s.selectionForeground.toAwtColor()
            g.drawString(sel, 10, y)
        }
    }
}
