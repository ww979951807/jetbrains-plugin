package com.haifasoft.haifassh.terminal

import com.intellij.ui.components.JBScrollBar
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.haifasoft.haifassh.terminal.theme.TerminalSchemes
import com.haifasoft.haifassh.terminal.theme.toTerminalColor
import java.awt.Adjustable
import javax.swing.JScrollBar

/**
 * A JediTerm widget whose default cell style (used for text the shell prints
 * without explicit color codes — e.g. the MOTD on first connect) is initialized
 * from the active [com.haifasoft.haifassh.terminal.theme.TerminalColorScheme].
 *
 * Without this, JediTerm's stock [StyleState] defaults to white-on-black, so the
 * first lines rendered before any SGR escape appeared white regardless of theme.
 *
 * It also uses the IDE's [JBScrollBar] instead of JediTerm's default plain Swing
 * scrollbar, so the terminal's scrollbar matches the rest of the IntelliJ UI
 * (theme-aware thin/overlay bar) rather than the stock blue Metal scrollbar.
 */
class ThemedJediTermWidget(settingsProvider: SettingsProvider) : JediTermWidget(settingsProvider) {

    // Assigned from within the super constructor (createDefaultStyle); lateinit so a
    // property initializer doesn't overwrite it afterwards.
    private lateinit var styleState: StyleState

    override fun createDefaultStyle(): StyleState {
        val state = StyleState()
        state.setDefaultStyle(currentDefaultStyle())
        styleState = state
        return state
    }

    /** Use the IDE-themed scrollbar so it matches IntelliJ instead of the Metal look. */
    override fun createScrollBar(): JScrollBar = JBScrollBar(Adjustable.VERTICAL)

    /** Re-reads the active scheme into the default style and repaints (theme/scheme switch). */
    fun reapplyScheme() {
        if (::styleState.isInitialized) {
            styleState.setDefaultStyle(currentDefaultStyle())
        }
        runCatching { terminalPanel.repaint() }
    }

    private fun currentDefaultStyle(): TextStyle {
        val scheme = TerminalSchemes.active()
        return TextStyle(scheme.foreground.toTerminalColor(), scheme.background.toTerminalColor())
    }
}
