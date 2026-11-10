package com.haifasoft.haifassh.terminal.theme

import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.core.Color as JediColor

/**
 * A JediTerm [ColorPalette] backed by a [TerminalColorScheme]. Indices 0–15 come
 * from the scheme's ANSI colors; 16–255 use the standard xterm 256-color cube and
 * grayscale ramp so programs that emit 256-color escapes still render correctly.
 */
class SchemeColorPalette(scheme: TerminalColorScheme) : ColorPalette() {

    private val ansi: IntArray = scheme.ansi
    private val fallback: Int = scheme.foreground

    override fun getForegroundByColorIndex(colorIndex: Int): JediColor = colorAt(colorIndex)

    override fun getBackgroundByColorIndex(colorIndex: Int): JediColor = colorAt(colorIndex)

    private fun colorAt(index: Int): JediColor = when (index) {
        in 0..15 -> ansi[index].toJediColor()
        in 16..231 -> cube(index - 16)
        in 232..255 -> gray(index - 232)
        else -> fallback.toJediColor()
    }

    /** xterm 6×6×6 color cube. */
    private fun cube(i: Int): JediColor {
        val r = i / 36
        val g = (i / 6) % 6
        val b = i % 6
        return JediColor(level(r), level(g), level(b))
    }

    private fun level(component: Int): Int = if (component == 0) 0 else 55 + component * 40

    /** xterm 24-step grayscale ramp. */
    private fun gray(i: Int): JediColor {
        val v = 8 + i * 10
        return JediColor(v, v, v)
    }
}
