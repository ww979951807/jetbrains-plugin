package com.haifasoft.haifassh.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.haifasoft.haifassh.model.JumpHost
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Exports the SSH session tree to a portable file and imports it back, optionally
 * carrying credentials. Import always assigns fresh ids and remaps folder
 * references, so importing never overwrites existing sessions — it merges.
 *
 * Config-only exports are plain XML. Exports that include credentials are
 * **encrypted** with a user passphrase (see [ExportCrypto]) so secrets are never
 * written to disk in cleartext.
 */
object SshSessionTransfer {

    /** Result of an import, for user feedback. */
    data class ImportResult(val folders: Int, val sessions: Int, val secretsRestored: Boolean)

    /** Thrown when an encrypted bundle can't be read (missing/incorrect passphrase). */
    class DecryptionException(message: String) : Exception(message)

    // ---- Export ------------------------------------------------------------

    /**
     * @param passphrase required (non-null, non-empty) when [includeSecrets] is true;
     * the bundle is then encrypted. Ignored for config-only exports.
     */
    fun export(target: Path, includeSecrets: Boolean, passphrase: CharArray? = null) {
        val service = SshSessionService.getInstance()
        val bundle = SshExportBundle().apply {
            this.includesSecrets = includeSecrets
            folders = service.folders().map { it.copyForExport() }.toMutableList()
            sessions = service.sessions().map { it.toExport(includeSecrets) }.toMutableList()
        }
        val xml = JDOMUtil.write(XmlSerializer.serialize(bundle))
        val output = if (includeSecrets) {
            require(passphrase != null && passphrase.isNotEmpty()) {
                "A passphrase is required to export credentials"
            }
            ExportCrypto.encrypt(xml, passphrase)
        } else {
            xml
        }
        Files.writeString(target, output)
        restrictToOwner(target)
    }

    /**
     * Best-effort tightening of the export file to owner-only access, so other
     * local users can't read it (matters most for encrypted bundles, but applied
     * always since the file still lists hosts/usernames). POSIX → `rw-------`;
     * elsewhere → strip group/other read & write via the File API.
     */
    private fun restrictToOwner(target: Path) {
        runCatching {
            val view = Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView::class.java)
            if (view != null) {
                Files.setPosixFilePermissions(
                    target,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            } else {
                target.toFile().apply {
                    setReadable(false, false); setReadable(true, true)
                    setWritable(false, false); setWritable(true, true)
                }
            }
        }
    }

    private fun SshFolder.copyForExport(): SshFolder = SshFolder().also {
        it.id = id
        it.name = name
        it.parentId = parentId
        it.color = color
        it.position = position
    }

    private fun SshSession.toExport(includeSecrets: Boolean): SshSessionExport {
        val store = SshCredentialStore
        return SshSessionExport().also { e ->
            e.id = id
            e.name = name
            e.folderId = folderId
            e.host = host
            e.port = port
            e.username = username
            e.authType = authType
            e.privateKeyPath = privateKeyPath
            e.protocol = protocol
            e.savePassword = savePassword
            e.allowLegacyAlgorithms = allowLegacyAlgorithms
            e.iconKey = iconKey
            e.color = color
            e.position = position
            e.jumpHosts = jumpHosts.mapIndexed { index, jump ->
                JumpHostExport().also { j ->
                    j.host = jump.host
                    j.port = jump.port
                    j.username = jump.username
                    j.authType = jump.authType
                    j.privateKeyPath = jump.privateKeyPath
                    if (includeSecrets) {
                        j.password = store.getJumpPassword(id, index)
                        j.passphrase = store.getJumpPassphrase(id, index)
                    }
                }
            }.toMutableList()
            if (includeSecrets) {
                e.password = store.getPassword(id)
                e.passphrase = store.getPassphrase(id)
            }
        }
    }

    // ---- Import ------------------------------------------------------------

    /** True if [source] is an encrypted bundle and therefore needs a passphrase. */
    fun isEncrypted(source: Path): Boolean =
        runCatching { ExportCrypto.isEncrypted(Files.readString(source)) }.getOrDefault(false)

    /**
     * @param passphrase required when [isEncrypted] is true for this file.
     * @throws DecryptionException when the passphrase is missing or incorrect.
     */
    fun import(source: Path, passphrase: CharArray? = null): ImportResult {
        val raw = Files.readString(source)
        val xml = if (ExportCrypto.isEncrypted(raw)) {
            if (passphrase == null || passphrase.isEmpty()) {
                throw DecryptionException("This export is encrypted; a passphrase is required.")
            }
            try {
                ExportCrypto.decrypt(raw, passphrase)
            } catch (t: Throwable) {
                throw DecryptionException("Incorrect passphrase or corrupted file.")
            }
        } else {
            raw
        }
        val element = JDOMUtil.load(StringReader(xml))
        val bundle = XmlSerializer.deserialize(element, SshExportBundle::class.java)

        // Fresh ids keep the import a merge — existing sessions are never clobbered.
        val folderIdMap = HashMap<String, String>()
        val newFolders = bundle.folders.map { f ->
            SshFolder().also {
                it.id = UUID.randomUUID().toString()
                it.name = f.name
                it.color = f.color
                it.position = f.position
                folderIdMap[f.id] = it.id
            }
        }
        // Remap parents now that every folder has a new id.
        bundle.folders.forEachIndexed { i, f ->
            newFolders[i].parentId = folderIdMap[f.parentId] ?: ""
        }

        val store = SshCredentialStore
        val newSessions = bundle.sessions.map { s ->
            val session = SshSession().also {
                it.id = UUID.randomUUID().toString()
                it.name = s.name
                it.folderId = folderIdMap[s.folderId] ?: ""
                it.host = s.host
                it.port = s.port
                it.username = s.username
                it.authType = s.authType
                it.privateKeyPath = s.privateKeyPath
                it.protocol = s.protocol
                it.savePassword = s.savePassword
                it.allowLegacyAlgorithms = s.allowLegacyAlgorithms
                it.iconKey = s.iconKey
                it.color = s.color
                it.position = s.position
                it.jumpHosts = s.jumpHosts.map { j ->
                    JumpHost().also { h ->
                        h.host = j.host
                        h.port = j.port
                        h.username = j.username
                        h.authType = j.authType
                        h.privateKeyPath = j.privateKeyPath
                    }
                }.toMutableList()
            }
            if (bundle.includesSecrets) {
                store.setPassword(session.id, s.password)
                store.setPassphrase(session.id, s.passphrase)
                s.jumpHosts.forEachIndexed { index, j ->
                    store.setJumpPassword(session.id, index, j.password)
                    store.setJumpPassphrase(session.id, index, j.passphrase)
                }
            }
            session
        }

        SshSessionService.getInstance().importBundle(newFolders, newSessions)
        return ImportResult(newFolders.size, newSessions.size, bundle.includesSecrets)
    }
}
