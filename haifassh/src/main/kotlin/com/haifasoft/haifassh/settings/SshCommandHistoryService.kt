package com.haifasoft.haifassh.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection

/** One recorded command line and when it was run (epoch millis). */
class CommandHistoryEntry {
    var command: String = ""
    var epochMillis: Long = 0L
}

/** All recorded commands for a single host, keyed by [hostKey]. */
class HostCommandHistory {
    var hostKey: String = ""

    @get:XCollection(style = XCollection.Style.v2)
    var entries: MutableList<CommandHistoryEntry> = mutableListOf()
}

/** Serialized state held by [SshCommandHistoryService]. */
class CommandHistoryState {
    @get:XCollection(style = XCollection.Style.v2)
    var hosts: MutableList<HostCommandHistory> = mutableListOf()
}

/**
 * Application-level, persisted history of commands typed into SSH shells, keyed
 * by host. Fed by [com.haifasoft.haifassh.terminal.TerminalCommandRecorder] as
 * the user presses Enter, and read by the terminal's History tab. Persisted to
 * <config>/options/haifassh-command-history.xml so it survives reconnects and
 * IDE restarts.
 */
@Service(Service.Level.APP)
// Not roamable: typed commands can carry hostnames, tokens or inline secrets, so they must
// stay on this machine rather than travel through Settings Sync.
@State(
    name = "HaifaSshCommandHistory",
    storages = [Storage("haifassh-command-history.xml", roamingType = RoamingType.DISABLED)],
)
class SshCommandHistoryService : PersistentStateComponent<CommandHistoryState> {

    private var state = CommandHistoryState()

    override fun getState(): CommandHistoryState = state

    override fun loadState(loaded: CommandHistoryState) {
        state = loaded
    }

    /** Normalizes a session host into the key used to bucket history. */
    fun hostKey(host: String): String = host.trim().lowercase()

    /** Snapshot of the recorded commands for [hostKey], oldest first. */
    fun history(hostKey: String): List<CommandHistoryEntry> = synchronized(this) {
        bucket(hostKey)?.entries?.toList() ?: emptyList()
    }

    /**
     * Records one executed command for [hostKey]. Blank lines are ignored, and a
     * command identical to the immediately-previous one is skipped (so holding a
     * key or re-running the same line doesn't flood history). Capped to the most
     * recent [MAX_PER_HOST] entries. Fires [TOPIC] afterwards.
     */
    fun record(hostKey: String, command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        synchronized(this) {
            val host = bucket(hostKey) ?: HostCommandHistory().also {
                it.hostKey = hostKey
                state.hosts.add(it)
            }
            if (host.entries.lastOrNull()?.command == trimmed) return@synchronized
            host.entries.add(CommandHistoryEntry().apply {
                this.command = trimmed
                this.epochMillis = System.currentTimeMillis()
            })
            while (host.entries.size > MAX_PER_HOST) host.entries.removeAt(0)
        }
        fireChanged(hostKey)
    }

    /** Clears all recorded history for [hostKey]. */
    fun clear(hostKey: String) {
        synchronized(this) { state.hosts.removeIf { it.hostKey == hostKey } }
        fireChanged(hostKey)
    }

    private fun bucket(hostKey: String): HostCommandHistory? =
        state.hosts.firstOrNull { it.hostKey == hostKey }

    private fun fireChanged(hostKey: String) {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).historyChanged(hostKey)
    }

    fun interface Listener {
        fun historyChanged(hostKey: String)
    }

    companion object {
        const val MAX_PER_HOST = 2000

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("HaifaSSH command history changed", Listener::class.java)

        fun getInstance(): SshCommandHistoryService = service()
    }
}
