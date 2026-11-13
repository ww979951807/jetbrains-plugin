package com.haifasoft.haifassh.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollBar
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionProvider
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.haifasoft.haifassh.terminal.theme.TerminalSchemes
import com.haifasoft.haifassh.terminal.theme.toTerminalColor
import java.awt.Adjustable
import java.awt.Point
import javax.swing.JPopupMenu
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
 *
 * The right-click menu is rendered through IntelliJ's native popup machinery
 * ([JBPopupFactory]) instead of JediTerm's stock Swing [JPopupMenu], so it gets
 * the platform look — native row height, padding and separators — matching the
 * built-in Terminal tool window.
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

    /** Swap in a panel whose popup menu is built with the IntelliJ-native renderer. */
    override fun createTerminalPanel(
        settingsProvider: SettingsProvider,
        styleState: StyleState,
        textBuffer: TerminalTextBuffer,
    ): TerminalPanel = NativePopupTerminalPanel(settingsProvider, textBuffer, styleState)

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

/**
 * A [TerminalPanel] whose [createPopupMenu] converts the provider's JediTerm
 * [TerminalAction]s into AnActions rendered by [JBPopupFactory] — the same
 * native popup styling as the rest of the IDE.
 *
 * JediTerm's mouse handler calls [JPopupMenu.show] on the returned menu, so the
 * override shows the native popup at the same screen position instead; the
 * returned Swing menu itself is never made visible.
 */
private class NativePopupTerminalPanel(
    settingsProvider: SettingsProvider,
    textBuffer: TerminalTextBuffer,
    styleState: StyleState,
) : TerminalPanel(settingsProvider, textBuffer, styleState) {

    override fun createPopupMenu(provider: TerminalActionProvider): JPopupMenu {
        val group = DefaultActionGroup()
        for (action in provider.actions) {
            if (action.isHidden) continue
            if (action.isSeparated) group.addSeparator()
            group.add(action.toAnAction())
        }
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataContext.EMPTY_CONTEXT,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)

        return object : JPopupMenu() {
            override fun show(invoker: java.awt.Component?, x: Int, y: Int) {
                val component = invoker ?: return
                val screen = Point(x, y).also { location ->
                    val origin = component.locationOnScreen
                    location.translate(origin.x, origin.y)
                }
                popup.showInScreenCoordinates(component, screen)
            }
        }
    }

    private fun TerminalAction.toAnAction(): AnAction {
        val name = getName()
        return object : AnAction(name, null, null) {
            override fun actionPerformed(e: AnActionEvent) {
                // JediTerm actions take the triggering KeyEvent; null matches what its
                // own Swing menu items pass when clicked.
                actionPerformed(null)
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isEnabled(null)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }
    }
}
