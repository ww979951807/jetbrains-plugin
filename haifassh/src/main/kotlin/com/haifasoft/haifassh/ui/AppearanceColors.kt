package com.haifasoft.haifassh.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Helpers for the user-chosen tree colors. Colors are persisted as `#RRGGBB`
 * strings on [com.haifasoft.haifassh.model.SshSession]/`SshFolder`.
 */
object AppearanceColors {

    /** Tint used for a session icon when the user hasn't picked a color of their own. */
    val DEFAULT_SESSION_TINT: JBColor = JBColor(Color(0x3592C4), Color(0x4A9FD4))

    /** A small ready-made palette offered as quick swatches in the color picker. */
    val PALETTE: List<Color> = listOf(
        Color(0x3592C4), // blue
        Color(0x59A869), // green
        Color(0xD9534F), // red
        Color(0xE8A33D), // amber
        Color(0x9B59B6), // purple
        Color(0x16A39B), // teal
        Color(0xEC6FAB), // pink
        Color(0x8A929E), // gray
    )

    /** Parses a stored `#RRGGBB` value, or null when blank/invalid. */
    fun parse(hex: String): Color? {
        val s = hex.trim()
        if (s.isEmpty()) return null
        return try {
            ColorUtil.fromHex(s)
        } catch (_: Exception) {
            null
        }
    }

    /** Serializes a color to a `#RRGGBB` string for persistence. */
    fun toHex(color: Color): String = "#" + ColorUtil.toHex(color)
}
