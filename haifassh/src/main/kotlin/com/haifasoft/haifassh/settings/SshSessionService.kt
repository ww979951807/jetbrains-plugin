package com.haifasoft.haifassh.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import java.util.UUID

/**
 * Application-level store of the SSH session tree, persisted to
 * <config>/options/haifassh-sessions.xml. Secrets are kept separately in
 * [SshCredentialStore]; only non-secret metadata lives here.
 */
@Service(Service.Level.APP)
@State(name = "HaifaSshSessions", storages = [Storage("haifassh-sessions.xml")])
class SshSessionService : PersistentStateComponent<SshSessionState> {

    private var state = SshSessionState()

    override fun getState(): SshSessionState = state

    override fun loadState(loaded: SshSessionState) {
        state = loaded
    }

    // ---- Read --------------------------------------------------------------

    fun folders(): List<SshFolder> = state.folders.toList()

    fun sessions(): List<SshSession> = state.sessions.toList()

    /**
     * Sub-folders of [parentId] in display order: manual [SshFolder.position]
     * first, name as the tie-breaker so a never-reordered tree stays alphabetical.
     */
    fun foldersIn(parentId: String): List<SshFolder> =
        state.folders.filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))

    /** Sessions of [folderId] in display order — see [foldersIn] for the ordering rule. */
    fun sessionsIn(folderId: String): List<SshSession> =
        state.sessions.filter { it.folderId == folderId }
            .sortedWith(compareBy({ it.position }, { it.displayName().lowercase() }))

    fun findSession(id: String): SshSession? = state.sessions.firstOrNull { it.id == id }

    fun findFolder(id: String): SshFolder? = state.folders.firstOrNull { it.id == id }

    // ---- Write -------------------------------------------------------------

    fun addFolder(name: String, parentId: String): SshFolder {
        val folder = SshFolder().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            this.parentId = parentId
        }
        state.folders.add(folder)
        fireChanged()
        return folder
    }

    fun renameFolder(id: String, name: String) {
        findFolder(id)?.let {
            it.name = name
            fireChanged()
        }
    }

    fun renameSession(id: String, name: String) {
        findSession(id)?.let {
            it.name = name
            fireChanged()
        }
    }

    /** Sets a folder's `#RRGGBB` tree color (blank to reset to default). */
    fun setFolderColor(id: String, color: String) {
        findFolder(id)?.let {
            it.color = color
            fireChanged()
        }
    }

    /** Sets a session's appearance in the tree. Pass blank to reset to the default. */
    fun setSessionAppearance(id: String, iconKey: String, color: String) {
        findSession(id)?.let {
            it.iconKey = iconKey
            it.color = color
            fireChanged()
        }
    }

    /** Bulk-sets the color of many sessions (blank resets). Fires a single change. */
    fun setSessionsColor(ids: Collection<String>, color: String) {
        var changed = false
        ids.forEach { id -> findSession(id)?.let { it.color = color; changed = true } }
        if (changed) fireChanged()
    }

    /** Bulk-sets the icon of many sessions. Fires a single change. */
    fun setSessionsIcon(ids: Collection<String>, iconKey: String) {
        var changed = false
        ids.forEach { id -> findSession(id)?.let { it.iconKey = iconKey; changed = true } }
        if (changed) fireChanged()
    }

    /** Bulk-sets the color of many folders (blank resets). Fires a single change. */
    fun setFoldersColor(ids: Collection<String>, color: String) {
        var changed = false
        ids.forEach { id -> findFolder(id)?.let { it.color = color; changed = true } }
        if (changed) fireChanged()
    }

    /** Upserts a session. Caller assigns [SshSession.id] (or leaves blank for a new one). */
    fun upsertSession(session: SshSession): SshSession {
        if (session.id.isBlank()) session.id = UUID.randomUUID().toString()
        val existing = state.sessions.indexOfFirst { it.id == session.id }
        if (existing >= 0) state.sessions[existing] = session else state.sessions.add(session)
        fireChanged()
        return session
    }

    /**
     * Adds pre-built folders and sessions in bulk (used by import). Callers are
     * responsible for assigning unique [SshFolder.id]/[SshSession.id] values and
     * remapping parent/folder references beforehand.
     */
    fun importBundle(newFolders: List<SshFolder>, newSessions: List<SshSession>) {
        state.folders.addAll(newFolders)
        state.sessions.addAll(newSessions)
        fireChanged()
    }

    fun deleteSession(id: String) {
        if (state.sessions.removeIf { it.id == id }) {
            SshCredentialStore.clearSession(id)
            fireChanged()
        }
    }

    /** Deletes many sessions, clearing each one's credentials. Fires a single change. */
    fun deleteSessions(ids: Collection<String>) {
        val idSet = ids.toSet()
        val removed = state.sessions.filter { it.id in idSet }
        if (removed.isEmpty()) return
        removed.forEach { SshCredentialStore.clearSession(it.id) }
        state.sessions.removeIf { it.id in idSet }
        fireChanged()
    }

    /** Deletes a folder and everything (sub-folders + sessions) under it, recursively. */
    fun deleteFolder(id: String) {
        val toRemove = collectSubtree(id)
        state.folders.removeIf { it.id in toRemove }
        val removedSessions = state.sessions.filter { it.folderId in toRemove }
        removedSessions.forEach { SshCredentialStore.clearSession(it.id) }
        state.sessions.removeIf { it.folderId in toRemove }
        fireChanged()
    }

    /** Deletes many folders (each with its whole subtree). Fires a single change. */
    fun deleteFolders(ids: Collection<String>) {
        val toRemove = linkedSetOf<String>()
        ids.forEach { toRemove.addAll(collectSubtree(it)) }
        if (toRemove.isEmpty()) return
        state.folders.removeIf { it.id in toRemove }
        val removedSessions = state.sessions.filter { it.folderId in toRemove }
        removedSessions.forEach { SshCredentialStore.clearSession(it.id) }
        state.sessions.removeIf { it.folderId in toRemove }
        fireChanged()
    }

    // ---- Reordering & moving (drag & drop) ---------------------------------

    /**
     * True when [folderId] is [ancestorId] itself or lives anywhere beneath it.
     * Used to reject a drag that would make a folder its own parent.
     */
    fun isInSubtree(folderId: String, ancestorId: String): Boolean {
        if (ancestorId.isBlank()) return false
        var current: String? = folderId
        val seen = hashSetOf<String>()
        while (!current.isNullOrBlank() && seen.add(current)) {
            if (current == ancestorId) return true
            current = findFolder(current)?.parentId
        }
        return false
    }

    /**
     * Moves [ids] into [targetFolderId] (blank = tree root) and places them at
     * [index] among that folder's sessions. [index] counts the target folder's
     * sessions *excluding* the ones being moved; pass a negative value to append.
     * The moved sessions keep their relative order.
     */
    fun moveSessions(ids: List<String>, targetFolderId: String, index: Int) {
        if (targetFolderId.isNotBlank() && findFolder(targetFolderId) == null) return
        val moving = ids.mapNotNull { findSession(it) }
        if (moving.isEmpty()) return

        val sourceFolders = moving.map { it.folderId }.toSet() - targetFolderId
        val siblings = sessionsIn(targetFolderId).filterNot { moved -> moving.any { it === moved } }
        val at = if (index < 0) siblings.size else index.coerceIn(0, siblings.size)

        moving.forEach { it.folderId = targetFolderId }
        siblings.toMutableList().apply { addAll(at, moving) }.forEachIndexed { i, s -> s.position = i }
        // Close the gaps left behind so the folders they came from stay tidy.
        sourceFolders.forEach { source -> sessionsIn(source).forEachIndexed { i, s -> s.position = i } }
        fireChanged()
    }

    /**
     * Moves folders [ids] under [targetParentId] (blank = tree root) at [index]
     * among that parent's sub-folders, with the same index convention as
     * [moveSessions]. A folder that would end up inside its own subtree is skipped.
     */
    fun moveFolders(ids: List<String>, targetParentId: String, index: Int) {
        if (targetParentId.isNotBlank() && findFolder(targetParentId) == null) return
        val moving = ids.mapNotNull { findFolder(it) }
            .filterNot { isInSubtree(targetParentId, it.id) }
        if (moving.isEmpty()) return

        val sourceParents = moving.map { it.parentId }.toSet() - targetParentId
        val siblings = foldersIn(targetParentId).filterNot { moved -> moving.any { it === moved } }
        val at = if (index < 0) siblings.size else index.coerceIn(0, siblings.size)

        moving.forEach { it.parentId = targetParentId }
        siblings.toMutableList().apply { addAll(at, moving) }.forEachIndexed { i, f -> f.position = i }
        sourceParents.forEach { source -> foldersIn(source).forEachIndexed { i, f -> f.position = i } }
        fireChanged()
    }

    private fun collectSubtree(rootId: String): Set<String> {
        val result = linkedSetOf(rootId)
        var frontier = listOf(rootId)
        while (frontier.isNotEmpty()) {
            val children = state.folders.filter { it.parentId in frontier }.map { it.id }
            val fresh = children.filter { result.add(it) }
            frontier = fresh
        }
        return result
    }

    private fun fireChanged() {
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .messageBus.syncPublisher(TOPIC).sessionsChanged()
    }

    fun interface Listener {
        fun sessionsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("HaifaSSH sessions changed", Listener::class.java)

        fun getInstance(): SshSessionService = service()
    }
}
