package com.haifasoft.haifassh.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * A [HostKeyRepository] that verifies a host key against several backing
 * repositories (e.g. the user's `~/.ssh/known_hosts` and the plugin-managed file),
 * matching OpenSSH semantics: a key is trusted if **any** repository already
 * contains it.
 *
 * - [readOnly] repositories are consulted first (priority) and never modified —
 *   notably the system `~/.ssh/known_hosts`, so the plugin never rewrites it.
 * - [writable] receives newly accepted keys (trust-on-first-use) and any
 *   changed-key updates, so those persist to the plugin-managed file only.
 */
class CompositeHostKeyRepository(
    private val writable: HostKeyRepository,
    private val readOnly: List<HostKeyRepository>,
) : HostKeyRepository {

    /** Priority order for lookups: read-only sources (system file) first, then ours. */
    private val all: List<HostKeyRepository> get() = readOnly + writable

    override fun check(host: String, key: ByteArray): Int {
        var changed = false
        for (repo in all) {
            when (repo.check(host, key)) {
                HostKeyRepository.OK -> return HostKeyRepository.OK
                HostKeyRepository.CHANGED -> changed = true
                else -> {} // NOT_INCLUDED — keep looking
            }
        }
        // A match in any repo wins; otherwise CHANGED (host known, key differs) beats NOT_INCLUDED.
        return if (changed) HostKeyRepository.CHANGED else HostKeyRepository.NOT_INCLUDED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = writable.add(hostkey, ui)

    override fun remove(host: String?, type: String?) = writable.remove(host, type)

    override fun remove(host: String?, type: String?, key: ByteArray?) = writable.remove(host, type, key)

    override fun getKnownHostsRepositoryID(): String = writable.knownHostsRepositoryID

    override fun getHostKey(): Array<HostKey> =
        all.flatMap { it.hostKey.asList() }.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        all.flatMap { it.getHostKey(host, type).asList() }.toTypedArray()
}
