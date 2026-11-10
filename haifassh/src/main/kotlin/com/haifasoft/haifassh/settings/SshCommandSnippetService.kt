package com.haifasoft.haifassh.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection
import com.haifasoft.haifassh.model.CommandSnippet
import java.util.UUID

/** Saved snippets scoped to a single session, keyed by [sessionId]. */
class SessionSnippets {
    var sessionId: String = ""

    @get:XCollection(style = XCollection.Style.v2)
    var snippets: MutableList<CommandSnippet> = mutableListOf()
}

/** Serialized state held by [SshCommandSnippetService]. */
class CommandSnippetState {
    @get:XCollection(style = XCollection.Style.v2)
    var global: MutableList<CommandSnippet> = mutableListOf()

    @get:XCollection(style = XCollection.Style.v2)
    var perSession: MutableList<SessionSnippets> = mutableListOf()
}

/**
 * Application-level store of saved command snippets, persisted to
 * <config>/options/haifassh-snippets.xml. Snippets come in two scopes: a shared
 * [global] library available in every terminal, and per-session lists tied to a
 * specific [com.haifasoft.haifassh.model.SshSession] id. Read by the terminal's
 * Commands tab; edited through [com.haifasoft.haifassh.ui.CommandSnippetDialog].
 */
@Service(Service.Level.APP)
@State(name = "HaifaSshSnippets", storages = [Storage("haifassh-snippets.xml")])
class SshCommandSnippetService : PersistentStateComponent<CommandSnippetState> {

    private var state = CommandSnippetState()

    override fun getState(): CommandSnippetState = state

    override fun loadState(loaded: CommandSnippetState) {
        state = loaded
    }

    // ---- Read --------------------------------------------------------------

    fun global(): List<CommandSnippet> = state.global.map { it.copyOf() }

    fun forSession(sessionId: String): List<CommandSnippet> =
        sessionBucket(sessionId)?.snippets?.map { it.copyOf() } ?: emptyList()

    // ---- Write -------------------------------------------------------------

    /**
     * Adds a snippet. [sessionId] blank means the global library; otherwise it is
     * added to that session's list. Returns the stored copy (with a fresh id).
     */
    fun add(command: String, description: String, sessionId: String): CommandSnippet {
        val snippet = CommandSnippet().apply {
            id = UUID.randomUUID().toString()
            this.command = command
            this.description = description
        }
        targetList(sessionId).add(snippet)
        fireChanged()
        return snippet
    }

    /** Updates an existing snippet's command/description within [sessionId] scope. */
    fun update(id: String, command: String, description: String, sessionId: String) {
        targetList(sessionId).firstOrNull { it.id == id }?.let {
            it.command = command
            it.description = description
            fireChanged()
        }
    }

    /** Removes a snippet by id from the given [sessionId] scope (blank = global). */
    fun remove(id: String, sessionId: String) {
        if (targetList(sessionId).removeIf { it.id == id }) fireChanged()
    }

    private fun targetList(sessionId: String): MutableList<CommandSnippet> =
        if (sessionId.isBlank()) {
            state.global
        } else {
            (sessionBucket(sessionId) ?: SessionSnippets().also {
                it.sessionId = sessionId
                state.perSession.add(it)
            }).snippets
        }

    private fun sessionBucket(sessionId: String): SessionSnippets? =
        state.perSession.firstOrNull { it.sessionId == sessionId }

    private fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).snippetsChanged()
    }

    fun interface Listener {
        fun snippetsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("HaifaSSH command snippets changed", Listener::class.java)

        fun getInstance(): SshCommandSnippetService = service()
    }
}
