package com.haifasoft.haifassh.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import com.intellij.util.containers.Convertor
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.fs.RemoteFileManager
import com.haifasoft.haifassh.model.SessionProtocol
import com.haifasoft.haifassh.model.SshFolder
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.HaifaSshSettings
import com.haifasoft.haifassh.settings.SshSessionService
import com.haifasoft.haifassh.settings.SshSessionTransfer
import com.haifasoft.haifassh.terminal.SshTerminalManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.EventObject
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellEditor
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The session manager UI: a folder/session tree with a toolbar to create,
 * edit, delete and connect sessions. Mirrors MobaXterm's left-hand session list.
 */
class SshSessionsPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    private val service = SshSessionService.getInstance()
    private val rootNode = DefaultMutableTreeNode("root")

    /**
     * Commits an in-place (Shift+F6) rename. We intentionally do NOT call
     * `super` — that would replace the node's [SshSession]/[SshFolder] user
     * object with the raw string. Instead we rename through the service, which
     * fires a change event and rebuilds the tree.
     */
    private val treeModel = object : DefaultTreeModel(rootNode) {
        override fun valueForPathChanged(path: TreePath, newValue: Any?) {
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
            val name = (newValue as? String)?.trim().orEmpty()
            if (name.isEmpty()) return
            val obj = node.userObject
            // Defer so the tree isn't rebuilt while Swing is still finishing the edit.
            SwingUtilities.invokeLater {
                when (obj) {
                    is SshFolder -> {
                        service.renameFolder(obj.id, name)
                        selectFolder(obj.id)
                    }
                    is SshSession -> {
                        service.renameSession(obj.id, name)
                        selectSession(obj.id)
                    }
                }
            }
        }
    }
    private val tree = Tree(treeModel)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        keepSelectionHomogeneous()
        tree.cellRenderer = SessionTreeCellRenderer()
        tree.emptyText.text = "No SSH sessions yet — click + to add one"

        // Drag & drop: move sessions/folders between folders, and reorder them.
        // ON_OR_INSERT gives both gestures — drop *on* a folder to move into it,
        // drop *between* two rows to place the drag at that spot.
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON_OR_INSERT
        tree.transferHandler = SessionTreeTransferHandler()

        // In-place rename (Shift+F6). The editor only starts programmatically,
        // so ordinary clicks never put a node into edit mode.
        tree.isEditable = true
        tree.cellEditor = SessionTreeCellEditor()
        tree.invokesStopCellEditing = true

        // Type-to-find: locate a session/folder by name quickly.
        TreeSpeedSearch.installOn(
            tree,
            true,
            Convertor { path ->
                when (val obj = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is SshFolder -> obj.name
                    is SshSession -> obj.displayName()
                    else -> null
                }
            },
        )

        RenameAction().registerCustomShortcutSet(
            CustomShortcutSet.fromString("shift F6"),
            tree,
            parentDisposable,
        )

        installContextMenu()

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedSession() != null) {
                    connectSelected()
                    return true
                }
                return false
            }
        }.installOn(tree)

        val decorator = ToolbarDecorator.createDecorator(tree)
            .setAddAction { addSession() }
            .setAddActionName("New Session")
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .addExtraAction(ConnectAction())
            .addExtraAction(OpenSftpAction())
            .addExtraAction(NewFolderAction())
            .addExtraAction(ImportAction())
            .addExtraAction(ExportAction())
            .addExtraAction(OpenInEditorToggleAction())
            .addExtraAction(TerminalColorsAction())
            .setPreferredSize(JBUI.size(280, 400))

        border = BorderFactory.createEmptyBorder()
        add(decorator.createPanel(), BorderLayout.CENTER)

        // Rebuild whenever the stored sessions change (e.g. from another window).
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(SshSessionService.TOPIC, SshSessionService.Listener { reload() })

        reload()
    }

    // ---- Tree building -----------------------------------------------------

    private fun reload() {
        rootNode.removeAllChildren()
        appendChildren(rootNode, "")
        treeModel.reload()
        expandAll()
    }

    private fun appendChildren(parent: DefaultMutableTreeNode, folderId: String) {
        for (folder in service.foldersIn(folderId)) {
            val node = DefaultMutableTreeNode(folder)
            parent.add(node)
            appendChildren(node, folder.id)
        }
        for (session in service.sessionsIn(folderId)) {
            parent.add(DefaultMutableTreeNode(session))
        }
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    // ---- Selection helpers -------------------------------------------------

    /** The lead (single) selected node — used by single-item actions. */
    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun selectedSession(): SshSession? = selectedNode()?.userObject as? SshSession

    private fun selectedFolder(): SshFolder? = selectedNode()?.userObject as? SshFolder

    private fun selectedNodes(): List<DefaultMutableTreeNode> =
        tree.selectionPaths?.mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode } ?: emptyList()

    private fun selectedSessions(): List<SshSession> = selectedNodes().mapNotNull { it.userObject as? SshSession }

    private fun selectedFolders(): List<SshFolder> = selectedNodes().mapNotNull { it.userObject as? SshFolder }

    /** True when the selection is non-empty and all of one kind (sessions xor folders). */
    private fun isAllSessions(): Boolean = selectedSessions().isNotEmpty() && selectedFolders().isEmpty()
    private fun isAllFolders(): Boolean = selectedFolders().isNotEmpty() && selectedSessions().isEmpty()

    /**
     * Restricts a multi-selection to a single kind: selecting a session clears any
     * selected folders and vice-versa, so bulk operations always act on one kind
     * (the user can't mix sessions and folders).
     */
    private var adjustingSelection = false
    private fun keepSelectionHomogeneous() {
        tree.selectionModel.addTreeSelectionListener {
            if (adjustingSelection) return@addTreeSelectionListener
            val paths = tree.selectionPaths ?: return@addTreeSelectionListener
            if (paths.size < 2) return@addTreeSelectionListener
            val lead = tree.leadSelectionPath ?: paths.last()
            val leadIsFolder = (lead.lastPathComponent as? DefaultMutableTreeNode)?.userObject is SshFolder
            val kept = paths.filter {
                val obj = (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                if (leadIsFolder) obj is SshFolder else obj is SshSession
            }
            if (kept.size != paths.size) {
                adjustingSelection = true
                tree.selectionPaths = kept.toTypedArray()
                adjustingSelection = false
            }
        }
    }

    /** Folder a new item should be created in, based on the current selection. */
    private fun targetFolderId(): String = when (val obj = selectedNode()?.userObject) {
        is SshFolder -> obj.id
        is SshSession -> obj.folderId
        else -> ""
    }

    // ---- Actions -----------------------------------------------------------

    private fun addSession() {
        val draft = SshSession().apply { folderId = targetFolderId() }
        val dialog = SshSessionDialog(project, draft, isNew = true)
        if (dialog.showAndGet()) {
            val saved = dialog.persist(service)
            selectSession(saved.id)
        }
    }

    private fun addFolder() {
        val name = Messages.showInputDialog(
            project,
            "Folder name:",
            "New Folder",
            null,
            "New Folder",
            null,
        )?.trim()
        if (!name.isNullOrEmpty()) {
            service.addFolder(name, targetFolderId())
        }
    }

    private fun editSelected() {
        selectedSession()?.let { session ->
            val dialog = SshSessionDialog(project, session.copyOf(), isNew = false)
            if (dialog.showAndGet()) {
                val saved = dialog.persist(service)
                selectSession(saved.id)
            }
            return
        }
        selectedFolder()?.let { folder ->
            val name = Messages.showInputDialog(
                project,
                "Folder name:",
                "Rename Folder",
                null,
                folder.name,
                null,
            )?.trim()
            if (!name.isNullOrEmpty()) {
                service.renameFolder(folder.id, name)
            }
        }
    }

    private fun removeSelected() {
        val sessions = selectedSessions()
        val folders = selectedFolders()
        if (sessions.isEmpty() && folders.isEmpty()) return

        val what = buildList {
            if (folders.isNotEmpty()) {
                add(if (folders.size == 1) "folder \"${folders.first().name}\" (and everything inside)" else "${folders.size} folders (and everything inside)")
            }
            if (sessions.isNotEmpty()) {
                add(if (sessions.size == 1) "session \"${sessions.first().displayName()}\"" else "${sessions.size} sessions")
            }
        }.joinToString(" and ")

        val confirm = Messages.showYesNoDialog(
            project,
            "Delete $what? Saved credentials will also be removed.",
            "Delete",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return
        if (folders.isNotEmpty()) service.deleteFolders(folders.map { it.id })
        if (sessions.isNotEmpty()) service.deleteSessions(sessions.map { it.id })
    }

    private fun connectSelected() {
        val session = selectedSession() ?: return
        if (session.protocol == SessionProtocol.SSH) {
            SshTerminalManager.getInstance(project).connect(session)
        } else {
            RemoteFileManager.getInstance(project).openBrowser(session)
        }
    }

    /** Opens a pre-filled "Duplicate" dialog for the selected session (secrets included). */
    private fun duplicateSelected() {
        val original = selectedSession() ?: return
        val copy = original.copyOf().apply {
            id = "" // blank → upsertSession assigns a fresh id, creating a new session
            name = "${original.name.ifBlank { original.displayName() }} (copy)"
        }
        val dialog = SshSessionDialog(project, copy, isNew = true, prefillSecretsFrom = original.id)
        if (dialog.showAndGet()) {
            val saved = dialog.persist(service)
            selectSession(saved.id)
        }
    }

    private fun exportSessions() {
        val count = service.sessions().size
        if (count == 0) {
            Messages.showInfoMessage(project, "There are no sessions to export.", "Export SSH Sessions")
            return
        }
        val options = ExportOptionsDialog(project, count)
        if (!options.showAndGet()) return

        val descriptor = FileSaverDescriptor("Export SSH Sessions", "Choose where to save the export file", "xml")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as java.nio.file.Path?, "haifassh-sessions.xml")
            ?: return

        try {
            SshSessionTransfer.export(wrapper.file.toPath(), options.includeSecrets, options.passphrase)
            val note = if (options.includeSecrets) " (encrypted with your passphrase)" else ""
            Messages.showInfoMessage(
                project,
                "Exported $count session(s)$note to:\n${wrapper.file.path}",
                "Export Complete",
            )
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Failed to export sessions: ${t.message ?: t.javaClass.simpleName}",
                "Export Failed",
            )
        }
    }

    private fun importSessions() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
            .withTitle("Import SSH Sessions")
            .withDescription("Select a HaifaSSH export file")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return

        val passphrase: CharArray? = if (SshSessionTransfer.isEncrypted(file.toNioPath())) {
            Messages.showPasswordDialog(
                project,
                "This export is encrypted. Enter its passphrase:",
                "Import SSH Sessions",
                null,
            )?.toCharArray() ?: return
        } else {
            null
        }

        try {
            val result = SshSessionTransfer.import(file.toNioPath(), passphrase)
            val secretsNote = if (result.secretsRestored) " Credentials were restored." else ""
            Messages.showInfoMessage(
                project,
                "Imported ${result.sessions} session(s) and ${result.folders} folder(s).$secretsNote",
                "Import Complete",
            )
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Failed to import sessions: ${t.message ?: t.javaClass.simpleName}",
                "Import Failed",
            )
        }
    }

    private fun selectSession(id: String) {
        val node = findSessionNode(rootNode, id) ?: return
        val path = javax.swing.tree.TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findSessionNode(parent: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val obj = child.userObject
            if (obj is SshSession && obj.id == id) return child
            findSessionNode(child, id)?.let { return it }
        }
        return null
    }

    private fun selectFolder(id: String) {
        val node = findFolderNode(rootNode, id) ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findFolderNode(parent: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val obj = child.userObject
            if (obj is SshFolder && obj.id == id) return child
            findFolderNode(child, id)?.let { return it }
        }
        return null
    }

    // ---- Rename & appearance ----------------------------------------------

    /** Starts in-place editing of the selected node (the Shift+F6 handler). */
    private fun renameSelected() {
        val path = tree.selectionPath ?: return
        val obj = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
        if (obj is SshSession || obj is SshFolder) {
            tree.startEditingAtPath(path)
        }
    }

    /** Applies one chosen color to every selected session, or every selected folder. */
    private fun setColorSelected() {
        val sessions = selectedSessions()
        val folders = selectedFolders()
        if (sessions.isEmpty() && folders.isEmpty()) return

        val current = sessions.firstOrNull()?.color ?: folders.firstOrNull()?.color ?: ""
        val initial = AppearanceColors.parse(current) ?: AppearanceColors.DEFAULT_SESSION_TINT
        val chosen = service<ColorChooserService>().showDialog(this, "Choose Color", initial) ?: return
        val hex = AppearanceColors.toHex(chosen)

        if (sessions.isNotEmpty()) {
            val ids = sessions.map { it.id }
            service.setSessionsColor(ids, hex)
            reselect(sessionIds = ids)
        } else {
            val ids = folders.map { it.id }
            service.setFoldersColor(ids, hex)
            reselect(folderIds = ids)
        }
    }

    private fun resetColorSelected() {
        val sessions = selectedSessions()
        val folders = selectedFolders()
        if (sessions.isNotEmpty()) {
            val ids = sessions.map { it.id }
            service.setSessionsColor(ids, "")
            reselect(sessionIds = ids)
        } else if (folders.isNotEmpty()) {
            val ids = folders.map { it.id }
            service.setFoldersColor(ids, "")
            reselect(folderIds = ids)
        }
    }

    /** Applies one chosen icon to every selected session. */
    private fun setIconSelected() {
        val sessions = selectedSessions()
        if (sessions.isEmpty()) return
        val dialog = IconPickerDialog(project, sessions.first().iconKey)
        if (dialog.showAndGet()) {
            val ids = sessions.map { it.id }
            service.setSessionsIcon(ids, dialog.chosenKey())
            reselect(sessionIds = ids)
        }
    }

    /** Re-selects the given items after a service change rebuilt the tree. */
    private fun reselect(sessionIds: Collection<String> = emptyList(), folderIds: Collection<String> = emptyList()) {
        val paths = buildList {
            sessionIds.forEach { id -> findSessionNode(rootNode, id)?.let { add(TreePath(it.path)) } }
            folderIds.forEach { id -> findFolderNode(rootNode, id)?.let { add(TreePath(it.path)) } }
        }
        if (paths.isNotEmpty()) {
            tree.selectionPaths = paths.toTypedArray()
            tree.scrollPathToVisible(paths.first())
        }
    }

    private fun installContextMenu() {
        val group = DefaultActionGroup().apply {
            add(NewSessionHereAction())
            add(NewSubfolderAction())
            addSeparator()
            add(ConnectAction())
            add(OpenSftpAction())
            add(OpenInToolWindowAction())
            add(DuplicateAction())
            addSeparator()
            add(RenameAction())
            add(SetColorAction())
            add(SetIconAction())
            add(ResetColorAction())
            addSeparator()
            add(EditAction())
            add(DeleteAction())
        }
        PopupHandler.installPopupMenu(tree, group, "HaifaSshSessionsTreePopup")
    }

    // ---- Drag & drop -------------------------------------------------------

    /** Payload of a drag: the ids of the dragged sessions, or of the dragged folders. */
    private class TreeTransfer(val sessionIds: List<String>, val folderIds: List<String>)

    /** Where a drop would land: a parent folder and an index within that parent. */
    private class DropTarget(val parentId: String, val index: Int)

    /**
     * Moves sessions and folders around the tree. A drag carries whichever kind is
     * selected (the selection is already homogeneous — see [keepSelectionHomogeneous]);
     * dropping on a folder moves into it, dropping between rows also sets the order.
     */
    private inner class SessionTreeTransferHandler : TransferHandler() {

        override fun getSourceActions(c: JComponent): Int = MOVE

        override fun createTransferable(c: JComponent): Transferable? {
            // Sort by row so a multi-item drag keeps the order it had in the tree.
            val objects = (tree.selectionPaths ?: emptyArray())
                .sortedBy { tree.getRowForPath(it) }
                .mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject }
            val payload = TreeTransfer(
                sessionIds = objects.filterIsInstance<SshSession>().map { it.id },
                folderIds = objects.filterIsInstance<SshFolder>().map { it.id },
            )
            if (payload.sessionIds.isEmpty() && payload.folderIds.isEmpty()) return null
            return object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(NODES_FLAVOR)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == NODES_FLAVOR

                override fun getTransferData(flavor: DataFlavor): Any =
                    if (flavor == NODES_FLAVOR) payload else throw UnsupportedFlavorException(flavor)
            }
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(NODES_FLAVOR)) return false
            val payload = payload(support) ?: return false
            return dropTarget(support, payload) != null
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(NODES_FLAVOR)) return false
            val payload = payload(support) ?: return false
            val target = dropTarget(support, payload) ?: return false
            // Defer so the tree isn't rebuilt while Swing is still finishing the drop.
            SwingUtilities.invokeLater {
                if (payload.folderIds.isNotEmpty()) {
                    service.moveFolders(payload.folderIds, target.parentId, target.index)
                    reselect(folderIds = payload.folderIds)
                } else {
                    service.moveSessions(payload.sessionIds, target.parentId, target.index)
                    reselect(sessionIds = payload.sessionIds)
                }
            }
            return true
        }

        private fun payload(support: TransferSupport): TreeTransfer? =
            runCatching { support.transferable.getTransferData(NODES_FLAVOR) as TreeTransfer }.getOrNull()

        /**
         * Resolves the drop location to a parent folder plus an insert index, or null
         * when the drop must be refused (a folder can't be moved inside itself).
         */
        private fun dropTarget(support: TransferSupport, payload: TreeTransfer): DropTarget? {
            val location = support.dropLocation as? JTree.DropLocation ?: return null
            // A null path means the empty space below the last row: move to the tree root.
            val node = location.path?.lastPathComponent as? DefaultMutableTreeNode
                ?: return validated("", -1, payload)

            if (location.childIndex < 0) {
                // Dropped *on* a row: a folder takes the drag, a session hands it to its folder.
                return when (val obj = node.userObject) {
                    is SshFolder -> validated(obj.id, -1, payload)
                    is SshSession ->
                        if (obj.id in payload.sessionIds) null else validated(obj.folderId, -1, payload)
                    else -> validated("", -1, payload)
                }
            }

            // Dropped *between* two children: the tree lists a folder's sub-folders
            // before its sessions, so translate the child index into an index within
            // the dragged kind's own list, then discount the items being moved (they
            // are still in the list when the drag reorders inside its own parent).
            val parentId = (node.userObject as? SshFolder)?.id ?: ""
            val folderCount = service.foldersIn(parentId).size
            val raw = if (payload.folderIds.isNotEmpty()) {
                location.childIndex.coerceAtMost(folderCount)
            } else {
                (location.childIndex - folderCount).coerceAtLeast(0)
            }
            return validated(parentId, discountMoved(parentId, raw, payload), payload)
        }

        /** Rejects a move that would put a folder inside its own subtree. */
        private fun validated(parentId: String, index: Int, payload: TreeTransfer): DropTarget? {
            if (payload.folderIds.any { service.isInSubtree(parentId, it) }) return null
            return DropTarget(parentId, index)
        }

        /**
         * Turns an index into the parent's current children into the index the service
         * expects — one counted with the dragged items already taken out.
         */
        private fun discountMoved(parentId: String, index: Int, payload: TreeTransfer): Int {
            val ids = if (payload.folderIds.isNotEmpty()) {
                service.foldersIn(parentId).map { it.id }
            } else {
                service.sessionsIn(parentId).map { it.id }
            }
            val moving = (payload.folderIds + payload.sessionIds).toSet()
            return index - ids.take(index).count { it in moving }
        }
    }

    // ---- Toolbar actions ---------------------------------------------------

    private inner class ConnectAction : DumbAwareAction(
        "Connect",
        "Open an SSH terminal for the selected session",
        AllIcons.Actions.Execute,
    ) {
        override fun actionPerformed(e: AnActionEvent) = connectSelected()

        override fun update(e: AnActionEvent) {
            // Single session only — avoid accidentally opening many terminals.
            e.presentation.isEnabled = selectedNodes().size == 1 && selectedSession() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenSftpAction : DumbAwareAction(
        "Open File Browser",
        "Open the remote SFTP/FTP file browser for the selected session",
        AllIcons.Nodes.Folder,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedSession()?.let { RemoteFileManager.getInstance(project).openBrowser(it) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNodes().size == 1 && selectedSession() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenInToolWindowAction : DumbAwareAction(
        "Open in SFTP Tool Window",
        "Open this session in the dedicated SFTP Browser tool window",
        AllIcons.Nodes.Folder,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedSession()?.let { RemoteFileManager.getInstance(project).openInToolWindow(it) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNodes().size == 1 && selectedSession() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class DuplicateAction : DumbAwareAction(
        "Duplicate…",
        "Create a copy of the selected session, pre-filled with all its settings",
        AllIcons.Actions.Copy,
    ) {
        override fun actionPerformed(e: AnActionEvent) = duplicateSelected()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNodes().size == 1 && selectedSession() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class NewFolderAction : DumbAwareAction(
        "New Folder",
        "Create a folder",
        AllIcons.Actions.NewFolder,
    ) {
        override fun actionPerformed(e: AnActionEvent) = addFolder()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** Folder-only context action: add a new session inside the selected folder. */
    private inner class NewSessionHereAction : DumbAwareAction(
        "New Session Here",
        "Add a new SSH session inside the selected folder",
        AllIcons.General.Add,
    ) {
        override fun actionPerformed(e: AnActionEvent) = addSession()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedNodes().size == 1 && selectedFolder() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** Folder-only context action: add a subfolder inside the selected folder. */
    private inner class NewSubfolderAction : DumbAwareAction(
        "New Subfolder",
        "Create a folder inside the selected folder",
        AllIcons.Actions.NewFolder,
    ) {
        override fun actionPerformed(e: AnActionEvent) = addFolder()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedNodes().size == 1 && selectedFolder() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ImportAction : DumbAwareAction(
        "Import Sessions…",
        "Import SSH sessions from a file",
        AllIcons.ToolbarDecorator.Import,
    ) {
        override fun actionPerformed(e: AnActionEvent) = importSessions()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ExportAction : DumbAwareAction(
        "Export Sessions…",
        "Export all SSH sessions to a file",
        AllIcons.ToolbarDecorator.Export,
    ) {
        override fun actionPerformed(e: AnActionEvent) = exportSessions()

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.sessions().isNotEmpty()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class TerminalColorsAction : DumbAwareAction(
        "Terminal Colors…",
        "Choose the terminal color scheme or customize colors",
        AllIcons.General.Settings,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, TerminalAppearanceConfigurable::class.java)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenInEditorToggleAction : ToggleAction(
        "Open Terminals in Editor Tab",
        "When enabled, connecting opens the terminal as an editor tab; otherwise in the bottom tool window",
        AllIcons.Actions.MoveToWindow,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = HaifaSshSettings.getInstance().openInEditor

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            HaifaSshSettings.getInstance().openInEditor = state
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class RenameAction : DumbAwareAction(
        "Rename",
        "Rename the selected session or folder",
        null,
    ) {
        override fun actionPerformed(e: AnActionEvent) = renameSelected()

        override fun update(e: AnActionEvent) {
            // Inline rename is one node at a time.
            e.presentation.isEnabled = selectedNodes().size == 1
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class SetColorAction : DumbAwareAction(
        "Set Color…",
        "Pick a color for the selected sessions or folders",
        null,
    ) {
        override fun actionPerformed(e: AnActionEvent) = setColorSelected()

        override fun update(e: AnActionEvent) {
            val n = selectedNodes().size
            e.presentation.isEnabled = isAllSessions() || isAllFolders()
            e.presentation.text = if (n > 1) "Set Color for $n Items…" else "Set Color…"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class SetIconAction : DumbAwareAction(
        "Set Icon…",
        "Pick an icon for the selected sessions",
        null,
    ) {
        override fun actionPerformed(e: AnActionEvent) = setIconSelected()

        override fun update(e: AnActionEvent) {
            // Icons apply to sessions only (folders keep the folder icon).
            val n = selectedSessions().size
            e.presentation.isEnabled = isAllSessions()
            e.presentation.text = if (n > 1) "Set Icon for $n Sessions…" else "Set Icon…"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class ResetColorAction : DumbAwareAction(
        "Reset Color",
        "Restore the default color",
        null,
    ) {
        override fun actionPerformed(e: AnActionEvent) = resetColorSelected()

        override fun update(e: AnActionEvent) {
            val anyColored = selectedSessions().any { it.color.isNotBlank() } ||
                selectedFolders().any { it.color.isNotBlank() }
            e.presentation.isEnabled = (isAllSessions() || isAllFolders()) && anyColored
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class EditAction : DumbAwareAction(
        "Edit…",
        "Edit the selected session or folder",
        AllIcons.Actions.Edit,
    ) {
        override fun actionPerformed(e: AnActionEvent) = editSelected()

        override fun update(e: AnActionEvent) {
            // Editing opens a single dialog.
            e.presentation.isEnabled = selectedNodes().size == 1
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class DeleteAction : DumbAwareAction(
        "Delete",
        "Delete the selected session or folder",
        AllIcons.Actions.GC,
    ) {
        override fun actionPerformed(e: AnActionEvent) = removeSelected()

        override fun update(e: AnActionEvent) {
            val n = selectedNodes().size
            e.presentation.isEnabled = n > 0
            e.presentation.text = if (n > 1) "Delete $n Items" else "Delete"
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    // ---- In-place rename editor -------------------------------------------

    /**
     * Edits a node's name in place. [isCellEditable] returns true only for a
     * null event, so editing starts solely via [renameSelected]/Shift+F6 — never
     * on a click — keeping double-click-to-connect intact.
     */
    private inner class SessionTreeCellEditor : AbstractCellEditor(), TreeCellEditor {
        private val field = JBTextField()

        init {
            field.addActionListener { stopCellEditing() }
            field.registerKeyboardAction(
                { cancelCellEditing() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_FOCUSED,
            )
        }

        override fun getTreeCellEditorComponent(
            tree: JTree,
            value: Any?,
            isSelected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
        ): Component {
            field.text = when (val obj = (value as? DefaultMutableTreeNode)?.userObject) {
                is SshFolder -> obj.name
                is SshSession -> obj.name.ifBlank { obj.displayName() }
                else -> ""
            }
            SwingUtilities.invokeLater {
                field.selectAll()
                field.requestFocusInWindow()
            }
            return field
        }

        override fun getCellEditorValue(): Any = field.text.trim()

        override fun isCellEditable(e: EventObject?): Boolean = e == null
    }

    // ---- Renderer ----------------------------------------------------------

    private class SessionTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val obj = (value as? DefaultMutableTreeNode)?.userObject
            when (obj) {
                is SshFolder -> {
                    val color = AppearanceColors.parse(obj.color)
                    // Pass keepGray explicitly: calling the defaulted Kotlin overload emits a
                    // synthetic colorize$default(...) call whose signature changed in 2025.2+/2026.x,
                    // causing NoSuchMethodError. The explicit 3-arg call binds to the stable method.
                    icon = if (color != null) IconUtil.colorize(AllIcons.Nodes.Folder, color, false) else AllIcons.Nodes.Folder
                    append(obj.name, nameAttributes(color, selected))
                }

                is SshSession -> {
                    val color = AppearanceColors.parse(obj.color)
                    val tint = color ?: AppearanceColors.DEFAULT_SESSION_TINT
                    icon = IconUtil.colorize(SessionIcons.icon(obj.iconKey), tint, false)
                    append(obj.displayName(), nameAttributes(color, selected))
                    val target = buildString {
                        append("  ")
                        if (obj.username.isNotBlank()) append("${obj.username}@")
                        append(obj.host.ifBlank { "?" })
                        append(":${obj.port}")
                        if (obj.jumpHosts.isNotEmpty()) append(" via ${obj.jumpHosts.size} jump")
                    }
                    append(target, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                else -> append(obj?.toString() ?: "")
            }
        }

        /** Colored name when the user picked a color (and the row isn't selected, for contrast). */
        private fun nameAttributes(color: java.awt.Color?, selected: Boolean): SimpleTextAttributes =
            if (color != null && !selected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
    }

    private companion object {
        /**
         * Same-JVM object handoff: the drag never leaves this tree, so the payload
         * travels by reference rather than being serialized.
         */
        val NODES_FLAVOR: DataFlavor = DataFlavor(
            "${DataFlavor.javaJVMLocalObjectMimeType};class=${TreeTransfer::class.java.name}",
            "HaifaSSH session tree nodes",
            SshSessionsPanel::class.java.classLoader,
        )
    }
}
