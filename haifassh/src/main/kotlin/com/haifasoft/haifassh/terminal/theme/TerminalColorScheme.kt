package com.haifasoft.haifassh.terminal.theme

import com.jediterm.terminal.TerminalColor
import java.awt.Color
import com.jediterm.core.Color as JediColor

/**
 * A complete terminal color scheme: the default background/foreground, the
 * text-selection colors, the cursor color, and the 16 ANSI palette entries
 * (indices 0–7 normal, 8–15 bright). All colors are 0xRRGGBB ints.
 *
 * This is the runtime form; the persisted form is [com.haifasoft.haifassh.settings.StoredScheme].
 */
class TerminalColorScheme(
    val name: String,
    val background: Int,
    val foreground: Int,
    val selectionBackground: Int,
    val selectionForeground: Int,
    val cursor: Int,
    /** Exactly 16 entries. */
    val ansi: IntArray,
)

// ---- Color conversion helpers ---------------------------------------------

fun Int.toJediColor(): JediColor = JediColor((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

fun Int.toTerminalColor(): TerminalColor = TerminalColor((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

fun Int.toAwtColor(): Color = Color(this and 0xFFFFFF)

fun Color.toRgbInt(): Int = (red shl 16) or (green shl 8) or blue

fun Int.toHex(): String = "#%06X".format(this and 0xFFFFFF)

fun String.hexToRgb(): Int = removePrefix("#").trim().toInt(16) and 0xFFFFFF
