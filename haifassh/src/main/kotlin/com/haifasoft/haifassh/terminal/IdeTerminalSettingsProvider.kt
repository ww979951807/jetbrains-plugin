package com.haifasoft.haifassh.terminal

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.terminal.theme.SchemeColorPalette
import com.haifasoft.haifassh.terminal.theme.TerminalSchemes
import com.haifasoft.haifassh.terminal.theme.toTerminalColor

/**
 * Feeds the active [TerminalColorScheme][com.haifasoft.haifassh.terminal.theme.TerminalColorScheme]
 * (chosen in settings — a template, a custom scheme, or "Follow IDE Theme") into
 * JediTerm: default colors, the full 16-color ANSI palette, and selection colors.
 *
 * Colors are read live on every call, so re-painting after a theme or scheme
 * change immediately reflects the new colors.
 *
 * CJK fix (patched): the original relied on DefaultSettingsProvider's hard-coded
 * Windows font (Consolas), which has no CJK glyphs — jediterm 3.53 renders each
 * cell with a single font and no glyph fallback, so all Chinese output rendered
 * as boxes. Now the IDE console font is used, with a CJK-capable fallback.
 */
class IdeTerminalSettingsProvider : DefaultSettingsProvider() {

    private fun scheme() = TerminalSchemes.active()

    // ---- CJK fix ----

    private fun ideConsoleFont(): java.awt.Font = try {
        EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.CONSOLE_PLAIN)
    } catch (t: Throwable) {
        super.getTerminalFont()
    }

    override fun getTerminalFont(): java.awt.Font {
        val ide = ideConsoleFont()
        if (canRenderCjk(ide)) return ide
        return cjkFallback(ide.size) ?: ide
    }

    override fun getTerminalFontSize(): Float = ideConsoleFont().size2D

    private fun canRenderCjk(f: java.awt.Font?): Boolean =
        f != null && f.canDisplayUpTo(CJK_SAMPLE) == -1

    private fun cjkFallback(size: Int): java.awt.Font? {
        // NOTE: do NOT pre-filter by getAvailableFontFamilyNames() — on some Windows
        // systems the CJK families never appear in that list, yet Font(name) still
        // resolves them (verified on Win10: 181 families listed, none CJK, while
        // new Font("NSimSun") renders CJK fine).
        for (name in CJK_CANDIDATES) {
            val f = java.awt.Font(name, java.awt.Font.PLAIN, size)
            if (canRenderCjk(f)) return f
        }
        return null
    }

    companion object {
        private const val CJK_SAMPLE = "中文测试，。Ａ１·"
        private val CJK_CANDIDATES = arrayOf(
            "NSimSun", "SimSun", "Sarasa Mono SC", "Sarasa Term SC", "SimHei",
            "Microsoft YaHei", "Noto Sans Mono CJK SC", "Noto Sans CJK SC",
            "MS Gothic", "PingFang SC", "WenQuanYi Zen Hei"
        )
    }

    override fun getDefaultForeground(): TerminalColor = scheme().foreground.toTerminalColor()

    override fun getDefaultBackground(): TerminalColor = scheme().background.toTerminalColor()

    override fun getTerminalColorPalette(): ColorPalette = SchemeColorPalette(scheme())

    override fun getSelectionColor(): TextStyle {
        val s = scheme()
        return TextStyle(s.selectionForeground.toTerminalColor(), s.selectionBackground.toTerminalColor())
    }

    // Use our explicit selection colors instead of inverting fg/bg (which rendered
    // selections as a near-black block on dark themes).
    override fun useInverseSelectionColor(): Boolean = false

    // User-configurable scrollback (history) size, read when a terminal is created
    // so a large value can hold, e.g., the full output of `tail -f -n 10000`.
    override fun getBufferMaxLinesCount(): Int = HaifaSshSettings.getInstance().terminalBufferMaxLines
}
