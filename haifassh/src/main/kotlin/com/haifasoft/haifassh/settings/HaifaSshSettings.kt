package com.haifasoft.haifassh.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection
import com.haifasoft.haifassh.terminal.theme.TerminalColorScheme
import com.haifasoft.haifassh.terminal.theme.hexToRgb
import com.haifasoft.haifassh.terminal.theme.toHex

/** Persisted "Custom" terminal color scheme (hex strings so it serializes cleanly). */
class StoredScheme {
    var background: String = "#1E1E1E"
    var foreground: String = "#CCCCCC"
    var selectionBackground: String = "#264F78"
    var selectionForeground: String = "#FFFFFF"
    var cursor: String = "#CCCCCC"

    @get:XCollection(style = XCollection.Style.v2)
    var ansi: MutableList<String> = DEFAULT_ANSI.toMutableList()

    fun toScheme(): TerminalColorScheme {
        val palette = ansi.takeIf { it.size == 16 } ?: DEFAULT_ANSI
        return TerminalColorScheme(
            name = "Custom",
            background = background.hexToRgb(),
            foreground = foreground.hexToRgb(),
            selectionBackground = selectionBackground.hexToRgb(),
            selectionForeground = selectionForeground.hexToRgb(),
            cursor = cursor.hexToRgb(),
            ansi = IntArray(16) { palette[it].hexToRgb() },
        )
    }

    fun copyFrom(scheme: TerminalColorScheme) {
        background = scheme.background.toHex()
        foreground = scheme.foreground.toHex()
        selectionBackground = scheme.selectionBackground.toHex()
        selectionForeground = scheme.selectionForeground.toHex()
        cursor = scheme.cursor.toHex()
        ansi = scheme.ansi.map { it.toHex() }.toMutableList()
    }

    companion object {
        private val DEFAULT_ANSI = listOf(
            "#000000", "#CD3131", "#0DBC79", "#E5E510", "#2472C8", "#BC3FBC", "#11A8CD", "#E5E5E5",
            "#666666", "#F14C4C", "#23D18B", "#F5F543", "#3B8EEA", "#D670D6", "#29B8DB", "#FFFFFF",
        )
    }
}

/** Serialized plugin-wide preferences (not session data). */
class HaifaSshSettingsState {
    /** When true, connecting opens the terminal as an editor tab; otherwise in the bottom tool window. */
    var openInEditor: Boolean = true

    /** Name of the active terminal color scheme (see TerminalSchemes). */
    var terminalSchemeName: String = "Follow IDE Theme"

    /** The user's editable scheme, used when [terminalSchemeName] is "Custom". */
    var custom: StoredScheme = StoredScheme()

    /**
     * Maximum scrollback lines kept per terminal (JediTerm history buffer).
     * Higher = more history retained (e.g. for `tail -f -n 10000`), at more
     * memory. Applies to terminals opened after the change.
     */
    var terminalBufferMaxLines: Int = 5000

    /**
     * Where the per-terminal assistant panel (History + Commands tabs) docks
     * relative to the terminal: one of "right", "left", "top", "bottom".
     * Applies live to open terminals and to newly opened ones.
     */
    var assistantPanelPosition: String = "right"

    /**
     * Whether the per-terminal assistant panel (SFTP Files, History, Commands) is shown.
     * Shown by default as a split panel; user can hide it via close button or settings.
     * Applies live to open terminals and to newly opened ones.
     */
    var assistantPanelVisible: Boolean = true
}

/**
 * Application-level plugin preferences, persisted to
 * <config>/options/haifassh-settings.xml.
 */
@Service(Service.Level.APP)
@State(name = "HaifaSshSettings", storages = [Storage("haifassh-settings.xml")])
class HaifaSshSettings : PersistentStateComponent<HaifaSshSettingsState> {

    private var state = HaifaSshSettingsState()

    override fun getState(): HaifaSshSettingsState = state

    override fun loadState(loaded: HaifaSshSettingsState) {
        state = loaded
    }

    var openInEditor: Boolean
        get() = state.openInEditor
        set(value) {
            state.openInEditor = value
        }

    var terminalSchemeName: String
        get() = state.terminalSchemeName
        set(value) {
            state.terminalSchemeName = value
        }

    /** Clamped to a sane range; see [HaifaSshSettingsState.terminalBufferMaxLines]. */
    var terminalBufferMaxLines: Int
        get() = state.terminalBufferMaxLines.coerceIn(MIN_BUFFER_LINES, MAX_BUFFER_LINES)
        set(value) {
            state.terminalBufferMaxLines = value.coerceIn(MIN_BUFFER_LINES, MAX_BUFFER_LINES)
        }

    /** Docking side of the terminal assistant panel: "right" | "left" | "top" | "bottom". */
    var assistantPanelPosition: String
        get() = state.assistantPanelPosition
        set(value) {
            state.assistantPanelPosition = value
            ApplicationManager.getApplication().messageBus.syncPublisher(POSITION_TOPIC).positionChanged(value)
        }

    /** Whether the assistant panel is shown; toggling re-lays out open terminals live. */
    var assistantPanelVisible: Boolean
        get() = state.assistantPanelVisible
        set(value) {
            state.assistantPanelVisible = value
            ApplicationManager.getApplication().messageBus.syncPublisher(VISIBILITY_TOPIC).visibilityChanged(value)
        }

    fun customScheme(): TerminalColorScheme = state.custom.toScheme()

    fun setCustomScheme(scheme: TerminalColorScheme) {
        state.custom.copyFrom(scheme)
    }

    /** Notifies open terminals that the color scheme changed so they re-paint. */
    fun fireSchemeChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(SCHEME_TOPIC).schemeChanged()
    }

    fun interface SchemeListener {
        fun schemeChanged()
    }

    fun interface PositionListener {
        fun positionChanged(position: String)
    }

    fun interface VisibilityListener {
        fun visibilityChanged(visible: Boolean)
    }

    companion object {
        const val MIN_BUFFER_LINES = 100
        const val MAX_BUFFER_LINES = 1_000_000

        @JvmField
        val SCHEME_TOPIC: Topic<SchemeListener> =
            Topic.create("HaifaSSH terminal scheme changed", SchemeListener::class.java)

        @JvmField
        val POSITION_TOPIC: Topic<PositionListener> =
            Topic.create("HaifaSSH assistant panel position changed", PositionListener::class.java)

        @JvmField
        val VISIBILITY_TOPIC: Topic<VisibilityListener> =
            Topic.create("HaifaSSH assistant panel visibility changed", VisibilityListener::class.java)

        fun getInstance(): HaifaSshSettings = service()
    }
}
