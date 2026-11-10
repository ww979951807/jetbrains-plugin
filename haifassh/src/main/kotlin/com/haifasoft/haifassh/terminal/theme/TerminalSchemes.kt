package com.haifasoft.haifassh.terminal.theme

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.haifasoft.haifassh.settings.HaifaSshSettings

/**
 * Built-in terminal color templates plus resolution of the currently active
 * scheme (selected in settings), including the dynamic "Follow IDE Theme".
 */
object TerminalSchemes {

    const val FOLLOW_IDE = "Follow IDE Theme"
    const val CUSTOM = "Custom"

    // ANSI palettes (16 colors: 0-7 normal, 8-15 bright).
    private val DARK_ANSI = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )
    private val LIGHT_ANSI = intArrayOf(
        0x000000, 0xCD3131, 0x00BC00, 0x949800, 0x0451A5, 0xBC05BC, 0x0598BC, 0x555555,
        0x666666, 0xCD3131, 0x14CE14, 0xB5BA00, 0x0451A5, 0xBC05BC, 0x0598BC, 0xA5A5A5,
    )
    private val SOLARIZED_ANSI = intArrayOf(
        0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
        0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
    )
    private val MONOKAI_ANSI = intArrayOf(
        0x272822, 0xF92672, 0xA6E22E, 0xF4BF75, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF8F8F2,
        0x75715E, 0xF92672, 0xA6E22E, 0xF4BF75, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF9F8F5,
    )
    private val ONE_DARK_ANSI = intArrayOf(
        0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
        0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF,
    )

    /** Ready-to-use templates, in display order. */
    val BUILTINS: List<TerminalColorScheme> = listOf(
        TerminalColorScheme("Classic Dark", 0x1E1E1E, 0xCCCCCC, 0x264F78, 0xFFFFFF, 0xCCCCCC, DARK_ANSI),
        TerminalColorScheme("Light", 0xFFFFFF, 0x333333, 0xADD6FF, 0x000000, 0x333333, LIGHT_ANSI),
        TerminalColorScheme("Solarized Dark", 0x002B36, 0x839496, 0x073642, 0x93A1A1, 0x839496, SOLARIZED_ANSI),
        TerminalColorScheme("Solarized Light", 0xFDF6E3, 0x657B83, 0xEEE8D5, 0x586E75, 0x657B83, SOLARIZED_ANSI),
        TerminalColorScheme("Monokai", 0x272822, 0xF8F8F2, 0x49483E, 0xF8F8F2, 0xF8F8F2, MONOKAI_ANSI),
        TerminalColorScheme("One Dark", 0x282C34, 0xABB2BF, 0x3E4451, 0xABB2BF, 0xABB2BF, ONE_DARK_ANSI),
    )

    /** Names for the settings combo: Follow IDE, every built-in, then Custom. */
    fun names(): List<String> = buildList {
        add(FOLLOW_IDE)
        BUILTINS.forEach { add(it.name) }
        add(CUSTOM)
    }

    /** The scheme currently selected in settings, resolved to concrete colors. */
    fun active(): TerminalColorScheme = byName(HaifaSshSettings.getInstance().terminalSchemeName)

    fun byName(name: String): TerminalColorScheme = when (name) {
        FOLLOW_IDE -> fromIde()
        CUSTOM -> HaifaSshSettings.getInstance().customScheme()
        else -> BUILTINS.firstOrNull { it.name == name } ?: fromIde()
    }

    /** Derives a scheme from the IDE's active editor color scheme (dark or light). */
    fun fromIde(): TerminalColorScheme {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bg = scheme.defaultBackground
        val fg = scheme.defaultForeground
        val dark = ColorUtil.isDark(bg)
        val selBg = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: (if (dark) 0x264F78 else 0xADD6FF).toAwtColor()
        val selFg = scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR) ?: fg
        return TerminalColorScheme(
            name = FOLLOW_IDE,
            background = bg.toRgbInt(),
            foreground = fg.toRgbInt(),
            selectionBackground = selBg.toRgbInt(),
            selectionForeground = selFg.toRgbInt(),
            cursor = fg.toRgbInt(),
            ansi = if (dark) DARK_ANSI else LIGHT_ANSI,
        )
    }
}
