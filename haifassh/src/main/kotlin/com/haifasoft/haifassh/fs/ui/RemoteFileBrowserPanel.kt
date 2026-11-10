package com.haifasoft.haifassh.fs.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.haifasoft.haifassh.fs.RemoteFile
import com.haifasoft.haifassh.fs.RemoteFileSystem
import com.haifasoft.haifassh.model.SessionProtocol
import com.haifasoft.haifassh.model.SshSession
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class RemoteFileBrowserPanel(
    private val project: Project,
    private val session: SshSession,
    private val fs: RemoteFileSystem,
    private val parentDisposable: Disposable,
    private val onOpenTerminal: (() -> Unit)? = null,
    private val onCdInTerminal: ((String) -> Unit)? = null,
) : JBPanel<RemoteFileBrowserPanel>(BorderLayout()) {

    private var currentPath: String = "/"
    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var fileList = listOf<RemoteFile>()

    private val pathField = JBTextField()
    private val statusLabel = JBLabel("Loading...")
    private val tableModel = RemoteFileTableModel()
    private val table = JBTable(tableModel)
    private var typeAhead: FileTableTypeAhead? = null

    private var isLocalExplorerOpen: Boolean = false
    private var localViewMode: LocalViewMode = LocalViewMode.DOCKED_PINNED
    private var dockPosition: DockPosition = DockPosition.LEFT

    private val centerContainer = JPanel(BorderLayout())
    private val remoteCenterPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(table), BorderLayout.CENTER)
    }
    private val splitter = JBSplitter(false, 0.45f).apply {
        dividerWidth = JBUI.scale(4)
    }

    private val localExplorerPanel = LocalFileExplorerPanel(
        project = project,
        session = session,
        onUploadFiles = { files -> uploadLocalFiles(files, currentPath) },
        onViewModeChanged = { mode -> setLocalViewMode(mode) },
        onPositionChanged = { pos -> setDockPosition(pos) },
        onCloseRequested = { toggleLocalExplorer(false) },
    )

    private var localWindow: LocalFileExplorerWindow? = null

    init {
        border = JBUI.Borders.empty()
        setupTable()
        add(buildToolbar(), BorderLayout.NORTH)
        add(centerContainer, BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)
        setupDragAndDrop()
        updateCenterLayout()

        // Initialize path to remote home dir in background
        object : Task.Backgroundable(project, "Connecting file browser", false) {
            override fun run(indicator: ProgressIndicator) {
                val home = runCatching { fs.getHomeDirectory() }.getOrDefault("/")
                currentPath = home.ifBlank { "/" }
            }

            override fun onSuccess() {
                navigateTo(currentPath, addToHistory = true)
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Failed to fetch initial directory: ${error.message}")
            }
        }.queue()
    }

    private fun setupTable() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.autoCreateRowSorter = true
        table.rowHeight = JBUI.scale(22)
        table.setShowGrid(false)

        // Install SpeedSearch and File Explorer Type-ahead navigation (typing e.g. "Down" jumps to Downloads)
        typeAhead = FileTableTypeAhead.installOn(table) { modelRow ->
            tableModel.getFileAt(modelRow)?.name
        }

        // Custom renderers
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: javax.swing.JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): java.awt.Component {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                val modelRow = table.convertRowIndexToModel(row)
                val file = tableModel.getFileAt(modelRow)
                if (file != null) {
                    text = file.name
                    icon = when {
                        file.isDirectory -> AllIcons.Nodes.Folder
                        file.isLink -> AllIcons.Nodes.Symlink
                        else -> AllIcons.FileTypes.Any_type
                    }
                }
                return this
            }
        }

        // Align size to right
        table.columnModel.getColumn(1).cellRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = javax.swing.SwingConstants.RIGHT
        }

        // Double click handler
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val file = tableModel.getFileAt(table.convertRowIndexToModel(row)) ?: return
                        if (file.isDirectory) {
                            navigateTo(file.path, addToHistory = true)
                        } else {
                            downloadAndOpenFile(file)
                        }
                    }
                } else if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    showContextMenu(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }
        })

        // Keyboard navigation
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isConsumed) return
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val row = table.selectedRow
                        if (row >= 0) {
                            val file = tableModel.getFileAt(table.convertRowIndexToModel(row)) ?: return
                            if (file.isDirectory) {
                                navigateTo(file.path, addToHistory = true)
                                e.consume()
                            } else {
                                downloadAndOpenFile(file)
                            }
                        }
                    }
                    KeyEvent.VK_UP -> {
                        if (e.isAltDown || e.isMetaDown) {
                            navigateUp()
                            e.consume()
                        }
                    }
                    KeyEvent.VK_LEFT -> {
                        if (e.isAltDown || e.isMetaDown) {
                            navigateBack()
                            e.consume()
                        }
                    }
                    KeyEvent.VK_RIGHT -> {
                        if (e.isAltDown || e.isMetaDown) {
                            navigateForward()
                            e.consume()
                        }
                    }
                    KeyEvent.VK_F5 -> {
                        refreshCurrentDirectory()
                        e.consume()
                    }
                    KeyEvent.VK_DELETE -> {
                        if (typeAhead?.hasActiveBuffer() == true) {
                            return
                        }
                        val row = table.selectedRow
                        if (row >= 0) {
                            val file = tableModel.getFileAt(table.convertRowIndexToModel(row)) ?: return
                            confirmAndDelete(file)
                            e.consume()
                        }
                    }
                    KeyEvent.VK_C -> {
                        if (e.isControlDown || e.isMetaDown) {
                            val row = table.selectedRow
                            if (row >= 0) {
                                val file = tableModel.getFileAt(table.convertRowIndexToModel(row))
                                if (file != null) copySelectedFile(file)
                            }
                            e.consume()
                        }
                    }
                    KeyEvent.VK_V -> {
                        if (e.isControlDown || e.isMetaDown) {
                            pasteClipboard()
                            e.consume()
                        }
                    }
                }
            }
        })
    }

    private fun ensureLocalWindow(): LocalFileExplorerWindow {
        var w = localWindow
        if (w == null || !w.isDisplayable) {
            w = LocalFileExplorerWindow(project, session, localExplorerPanel) {
                toggleLocalExplorer(false)
            }
            localWindow = w
        }
        return w
    }

    private fun updateCenterLayout() {
        centerContainer.removeAll()
        localWindow?.detachPanel()
        splitter.firstComponent = null
        splitter.secondComponent = null

        if (!isLocalExplorerOpen) {
            localWindow?.isVisible = false
            centerContainer.add(remoteCenterPanel, BorderLayout.CENTER)
        } else if (localViewMode.isDocked) {
            localWindow?.isVisible = false
            val isVertical = (dockPosition == DockPosition.UP || dockPosition == DockPosition.DOWN)
            splitter.orientation = isVertical
            if (dockPosition == DockPosition.LEFT || dockPosition == DockPosition.UP) {
                splitter.firstComponent = localExplorerPanel
                splitter.secondComponent = remoteCenterPanel
            } else {
                splitter.firstComponent = remoteCenterPanel
                splitter.secondComponent = localExplorerPanel
            }
            centerContainer.add(splitter, BorderLayout.CENTER)
        } else {
            // WINDOW, FLOAT, UNDOCK
            centerContainer.add(remoteCenterPanel, BorderLayout.CENTER)
            val win = ensureLocalWindow()
            win.attachPanel()
            win.isVisible = true
            win.toFront()
        }

        localExplorerPanel.updateHeaderButtons()
        centerContainer.revalidate()
        centerContainer.repaint()
        revalidate()
        repaint()
    }

    fun toggleLocalExplorer(open: Boolean) {
        isLocalExplorerOpen = open
        if (open && !localViewMode.isDocked) {
            localViewMode = LocalViewMode.DOCKED_PINNED
            localExplorerPanel.setViewMode(LocalViewMode.DOCKED_PINNED)
        }
        updateCenterLayout()
    }

    fun setLocalViewMode(mode: LocalViewMode) {
        localViewMode = mode
        if (!isLocalExplorerOpen) {
            isLocalExplorerOpen = true
        }
        updateCenterLayout()
    }

    fun setDockPosition(pos: DockPosition) {
        dockPosition = pos
        if (!localViewMode.isDocked) {
            localViewMode = LocalViewMode.DOCKED_PINNED
            localExplorerPanel.setViewMode(LocalViewMode.DOCKED_PINNED)
        }
        if (!isLocalExplorerOpen) {
            isLocalExplorerOpen = true
        }
        updateCenterLayout()
    }

    private fun createVerticalSeparator(): JSeparator = JSeparator(SwingConstants.VERTICAL).apply {
        preferredSize = Dimension(JBUI.scale(6), JBUI.scale(20))
        foreground = UIUtil.getBoundsColor()
    }

    private fun buildToolbar(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6),
            )
        }

        // Row 1: Address bar (Full width above the icons)
        val addressBar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 4, 0)
        }
        pathField.addActionListener {
            val text = pathField.text.trim()
            if (text.isNotBlank()) {
                navigateTo(text, addToHistory = true)
            }
        }
        val goBtn = HoverIconButton("Go to path (Enter)", AllIcons.Actions.Forward) {
            val text = pathField.text.trim()
            if (text.isNotBlank()) {
                navigateTo(text, addToHistory = true)
            }
        }
        addressBar.add(pathField, BorderLayout.CENTER)
        addressBar.add(goBtn, BorderLayout.EAST)

        // Row 2: Icons bar (below the address bar)
        val iconBar = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val navButtons = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(HoverIconButton("Back (Alt+Left)", AllIcons.Actions.Back) { navigateBack() })
            add(HoverIconButton("Forward (Alt+Right)", AllIcons.Actions.Forward) { navigateForward() })
            add(HoverIconButton("Up Folder (Alt+Up)", AllIcons.Nodes.UpFolder) { navigateUp() })
            add(HoverIconButton("Refresh (F5)", AllIcons.Actions.Refresh) { refreshCurrentDirectory() })

            add(createVerticalSeparator())

            add(HoverIconButton("Toggle Local Files (Side-by-Side)", AllIcons.Nodes.Desktop) {
                toggleLocalExplorer(!isLocalExplorerOpen)
            })
        }

        val actionButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(HoverIconButton("New Folder", AllIcons.Actions.NewFolder) { promptNewFolder() })
            add(HoverIconButton("Upload File(s)...", AllIcons.Actions.Upload) { promptUploadFiles() })
            add(HoverIconButton("Download Selected", AllIcons.Actions.Download) { downloadSelectedFile() })
            add(HoverIconButton("Delete Selected (Del)", AllIcons.Actions.Cancel) { deleteSelectedFile() })

            // Button to open SSH terminal if available
            if (session.protocol != SessionProtocol.FTP && onOpenTerminal != null) {
                add(createVerticalSeparator())
                add(HoverIconButton("Open SSH Terminal", AllIcons.Debugger.Console) {
                    onOpenTerminal.invoke()
                })
            }
            if (onCdInTerminal != null) {
                add(HoverIconButton("cd Terminal Here", AllIcons.Actions.Execute) {
                    onCdInTerminal.invoke(currentPath)
                })
            }
        }

        iconBar.add(navButtons, BorderLayout.WEST)
        iconBar.add(actionButtons, BorderLayout.EAST)

        panel.add(addressBar, BorderLayout.NORTH)
        panel.add(iconBar, BorderLayout.SOUTH)
        return panel
    }

    private fun buildStatusBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 0, 0, 0),
                JBUI.Borders.empty(3, 8),
            )
            add(statusLabel, BorderLayout.WEST)
            val protocolLabel = JBLabel("${session.protocol.name}: ${session.username}@${session.host}").apply {
                foreground = JBColor.GRAY
            }
            add(protocolLabel, BorderLayout.EAST)
        }
    }

    private fun setupDragAndDrop() {
        val dropTargetListener = object : DropTargetAdapter() {
            override fun drop(dtde: DropTargetDropEvent) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    val transferable = dtde.transferable
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        if (!files.isNullOrEmpty()) {
                            val row = table.rowAtPoint(dtde.location)
                            val targetDir = if (row >= 0) {
                                val targetFile = tableModel.getFileAt(table.convertRowIndexToModel(row))
                                if (targetFile != null && targetFile.isDirectory) targetFile.path else currentPath
                            } else currentPath
                            uploadLocalFiles(files, targetDir)
                            dtde.dropComplete(true)
                            return
                        }
                    }
                    dtde.dropComplete(false)
                } catch (t: Throwable) {
                    dtde.dropComplete(false)
                    notifyError("Drag and drop upload failed: ${t.message}")
                }
            }
        }
        DropTarget(table, dropTargetListener)
        DropTarget(this, dropTargetListener)
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
        }
        val rows = table.selectedRows
        val selectedFiles = rows.map { tableModel.getFileAt(table.convertRowIndexToModel(it)) }.filterNotNull()
        val selectedFile = if (selectedFiles.size == 1) selectedFiles[0] else null

        val menu = JPopupMenu()
        if (selectedFile != null && !selectedFile.isDirectory) {
            menu.add(JMenuItem("Open in Editor", AllIcons.Actions.Edit).apply {
                addActionListener { downloadAndOpenFile(selectedFile) }
            })
            menu.add(JMenuItem("Download to...", AllIcons.Actions.Download).apply {
                addActionListener { promptDownloadTarget(selectedFile) }
            })
        } else if (selectedFiles.size > 1) {
            val nonDirFiles = selectedFiles.filter { !it.isDirectory }
            if (nonDirFiles.isNotEmpty()) {
                menu.add(JMenuItem("Download (${nonDirFiles.size} files) to...", AllIcons.Actions.Download).apply {
                    addActionListener { promptDownloadTargetForMultiple(nonDirFiles) }
                })
            }
        }

        menu.add(JMenuItem("Upload Files Here...", AllIcons.Actions.Upload).apply {
            addActionListener { promptUploadFiles() }
        })
        menu.add(JMenuItem(if (isLocalExplorerOpen) "Hide Local Files" else "Open Local Files (Side-by-Side)", AllIcons.Nodes.Desktop).apply {
            addActionListener { toggleLocalExplorer(!isLocalExplorerOpen) }
        })
        menu.add(JMenuItem("New Folder...", AllIcons.Actions.NewFolder).apply {
            addActionListener { promptNewFolder() }
        })

        if (selectedFile != null) {
            if (selectedFile.isDirectory && onCdInTerminal != null) {
                menu.add(JMenuItem("cd in Terminal", AllIcons.Debugger.Console).apply {
                    addActionListener { onCdInTerminal.invoke(selectedFile.path) }
                })
                menu.addSeparator()
            }
            menu.add(JMenuItem("Rename...", AllIcons.Actions.Edit).apply {
                addActionListener { promptRename(selectedFile) }
            })
            menu.add(JMenuItem("Delete", AllIcons.Actions.Cancel).apply {
                addActionListener { confirmAndDelete(selectedFile) }
            })
            menu.addSeparator()
            menu.add(JMenuItem("Copy", AllIcons.Actions.Copy).apply {
                addActionListener { copySelectedFile(selectedFile) }
            })
            menu.add(JMenuItem("Copy Remote Path", AllIcons.Actions.Copy).apply {
                addActionListener {
                    val sel = StringSelection(selectedFile.path)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        } else if (selectedFiles.size > 1) {
            menu.add(JMenuItem("Delete (${selectedFiles.size} items)", AllIcons.Actions.Cancel).apply {
                addActionListener { deleteSelectedFile() }
            })
            menu.addSeparator()
            menu.add(JMenuItem("Copy (${selectedFiles.size} paths)", AllIcons.Actions.Copy).apply {
                addActionListener {
                    val sel = StringSelection(selectedFiles.joinToString("\n") { it.path })
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        }

        menu.add(JMenuItem("Paste", AllIcons.Actions.MenuPaste).apply {
            isEnabled = canPaste()
            addActionListener { pasteClipboard() }
        })

        menu.addSeparator()
        menu.add(JMenuItem("Refresh", AllIcons.Actions.Refresh).apply {
            addActionListener { refreshCurrentDirectory() }
        })

        menu.show(e.component, e.x, e.y)
    }

    fun navigateTo(path: String, addToHistory: Boolean = false) {
        typeAhead?.clear()
        val targetPath = if (path.isBlank()) "/" else path
        pathField.text = targetPath
        statusLabel.text = "Loading $targetPath..."

        object : Task.Backgroundable(project, "Listing remote files", false) {
            private var result: List<RemoteFile>? = null

            override fun run(indicator: ProgressIndicator) {
                result = fs.listFiles(targetPath).sortedWith(
                    compareBy<RemoteFile> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
            }

            override fun onSuccess() {
                currentPath = targetPath
                pathField.text = targetPath
                fileList = result ?: emptyList()
                tableModel.setFiles(fileList)

                if (addToHistory) {
                    if (historyIndex < history.size - 1) {
                        history.subList(historyIndex + 1, history.size).clear()
                    }
                    history.add(targetPath)
                    historyIndex = history.size - 1
                }

                val dirCount = fileList.count { it.isDirectory }
                val fileCount = fileList.size - dirCount
                statusLabel.text = "${fileList.size} items ($dirCount folders, $fileCount files)"
            }

            override fun onThrowable(error: Throwable) {
                statusLabel.text = "Error loading $targetPath"
                notifyError("Failed to list files in $targetPath: ${error.message}")
            }
        }.queue()
    }

    private fun navigateUp() {
        if (currentPath == "/" || currentPath.isBlank()) return
        val lastSlash = currentPath.trimEnd('/').lastIndexOf('/')
        val parent = if (lastSlash <= 0) "/" else currentPath.substring(0, lastSlash)
        navigateTo(parent, addToHistory = true)
    }

    private fun navigateBack() {
        if (historyIndex > 0) {
            historyIndex--
            navigateTo(history[historyIndex], addToHistory = false)
        }
    }

    private fun navigateForward() {
        if (historyIndex < history.size - 1) {
            historyIndex++
            navigateTo(history[historyIndex], addToHistory = false)
        }
    }

    private fun refreshCurrentDirectory() {
        navigateTo(currentPath, addToHistory = false)
    }

    private fun promptNewFolder() {
        val name = Messages.showInputDialog(
            this,
            "Folder name:",
            "Create New Folder",
            Messages.getQuestionIcon()
        )?.trim() ?: return

        if (name.isBlank()) return
        val newPath = normalizeRemotePath(currentPath, name)

        object : Task.Backgroundable(project, "Creating folder $name", false) {
            override fun run(indicator: ProgressIndicator) {
                fs.createDirectory(newPath)
            }

            override fun onSuccess() {
                refreshCurrentDirectory()
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Failed to create folder $name: ${error.message}")
            }
        }.queue()
    }

    private fun promptRename(file: RemoteFile) {
        val newName = Messages.showInputDialog(
            this,
            "New name for ${file.name}:",
            "Rename",
            Messages.getQuestionIcon(),
            file.name,
            null
        )?.trim() ?: return

        if (newName.isBlank() || newName == file.name) return
        val newPath = normalizeRemotePath(currentPath, newName)

        object : Task.Backgroundable(project, "Renaming ${file.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                fs.rename(file.path, newPath)
            }

            override fun onSuccess() {
                refreshCurrentDirectory()
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Failed to rename ${file.name}: ${error.message}")
            }
        }.queue()
    }

    private fun confirmAndDelete(file: RemoteFile) {
        val type = if (file.isDirectory) "folder" else "file"
        val answer = Messages.showYesNoDialog(
            this,
            "Are you sure you want to delete the $type \"${file.name}\"?",
            "Delete $type",
            Messages.getWarningIcon()
        )
        if (answer != Messages.YES) return

        object : Task.Backgroundable(project, "Deleting ${file.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                fs.delete(file.path, file.isDirectory)
            }

            override fun onSuccess() {
                refreshCurrentDirectory()
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Failed to delete ${file.name}: ${error.message}")
            }
        }.queue()
    }

    private fun deleteSelectedFile() {
        val rows = table.selectedRows
        if (rows.isEmpty()) return
        val files = rows.map { tableModel.getFileAt(table.convertRowIndexToModel(it)) }.filterNotNull()
        if (files.isEmpty()) return

        if (files.size == 1) {
            confirmAndDelete(files[0])
        } else {
            val answer = Messages.showYesNoDialog(
                this,
                "Are you sure you want to delete ${files.size} selected items?",
                "Delete ${files.size} Items",
                Messages.getWarningIcon()
            )
            if (answer != Messages.YES) return

            object : Task.Backgroundable(project, "Deleting ${files.size} items", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    for ((index, file) in files.withIndex()) {
                        if (indicator.isCanceled) break
                        indicator.text = "Deleting ${file.name} (${index + 1}/${files.size})"
                        indicator.fraction = index.toDouble() / files.size.toDouble()
                        fs.delete(file.path, file.isDirectory)
                    }
                }

                override fun onSuccess() {
                    refreshCurrentDirectory()
                }

                override fun onThrowable(error: Throwable) {
                    notifyError("Failed to delete items: ${error.message}")
                }
            }.queue()
        }
    }

    private fun downloadSelectedFile() {
        val rows = table.selectedRows
        if (rows.isEmpty()) return
        val files = rows.map { tableModel.getFileAt(table.convertRowIndexToModel(it)) }.filterNotNull().filter { !it.isDirectory }
        if (files.isEmpty()) return

        if (files.size == 1) {
            promptDownloadTarget(files[0])
        } else {
            promptDownloadTargetForMultiple(files)
        }
    }

    private fun promptDownloadTargetForMultiple(files: List<RemoteFile>) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, this)
        val selected = chooser.choose(project)
        if (selected.isNotEmpty()) {
            val targetDir = File(selected[0].path)
            object : Task.Backgroundable(project, "Downloading ${files.size} files", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    for ((index, file) in files.withIndex()) {
                        if (indicator.isCanceled) break
                        indicator.text = "Downloading ${file.name} (${index + 1}/${files.size})"
                        indicator.fraction = index.toDouble() / files.size.toDouble()
                        fs.download(file.path, file.localTargetIn(targetDir)) { _, _ -> }
                    }
                }

                override fun onSuccess() {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("HaifaSSH")
                            .createNotification("Downloaded ${files.size} file(s) to ${targetDir.name}", NotificationType.INFORMATION)
                            .notify(project)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    notifyError("Download failed: ${error.message}")
                }
            }.queue()
        }
    }

    private fun promptUploadFiles() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, this)
        val selected = chooser.choose(project)
        if (selected.isNotEmpty()) {
            val files = selected.mapNotNull { it.canonicalPath?.let { p -> File(p) } }
            uploadLocalFiles(files, currentPath)
        }
    }

    private fun uploadLocalFiles(files: List<File>, targetRemoteDir: String) {
        object : Task.Backgroundable(project, "Uploading ${files.size} file(s)", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                for ((index, file) in files.withIndex()) {
                    if (indicator.isCanceled) break
                    indicator.text = "Uploading ${file.name} (${index + 1}/${files.size})"
                    indicator.fraction = 0.0
                    fs.upload(file, targetRemoteDir) { bytes, total ->
                        if (total > 0) {
                            indicator.fraction = bytes.toDouble() / total.toDouble()
                        }
                    }
                }
            }

            override fun onSuccess() {
                refreshCurrentDirectory()
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Upload failed: ${error.message}")
            }
        }.queue()
    }

    private fun promptDownloadTarget(file: RemoteFile) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, project, this)
        val selected = chooser.choose(project)
        if (selected.isNotEmpty()) {
            val destination = try {
                file.localTargetIn(File(selected[0].path))
            } catch (e: IOException) {
                notifyError(e.message ?: "Unsafe file name")
                return
            }
            downloadFileTo(file, destination)
        }
    }

    private fun downloadFileTo(file: RemoteFile, target: File, onComplete: ((File) -> Unit)? = null) {
        object : Task.Backgroundable(project, "Downloading ${file.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Downloading ${file.name}..."
                fs.download(file.path, target) { bytes, total ->
                    if (total > 0) {
                        indicator.fraction = bytes.toDouble() / total.toDouble()
                    }
                }
            }

            override fun onSuccess() {
                onComplete?.invoke(target)
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Download of ${file.name} failed: ${error.message}")
            }
        }.queue()
    }

    private fun downloadAndOpenFile(file: RemoteFile) {
        // Under the IDE's per-user system dir rather than the shared java.io.tmpdir, where
        // (on Linux) another local user could pre-create the folder or plant a symlink.
        val tempDir = File(PathManager.getSystemPath(), "haifassh/downloads/${session.id}")
        tempDir.mkdirs()
        val tempFile = try {
            file.localTargetIn(tempDir)
        } catch (e: IOException) {
            notifyError(e.message ?: "Unsafe file name")
            return
        }
        downloadFileTo(file, tempFile) { downloaded ->
            ApplicationManager.getApplication().invokeLater {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(downloaded)
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        }
    }

    private fun normalizeRemotePath(dir: String, name: String): String {
        val cleanDir = dir.trimEnd('/')
        return if (cleanDir.isEmpty()) "/$name" else "$cleanDir/$name"
    }

    private fun notifyError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("HaifaSSH")
                .createNotification(message, NotificationType.ERROR)
                .notify(project)
        }
    }

    private fun copySelectedFile(file: RemoteFile) {
        clipboardFile = RemoteClipboardItem(session.id, file)
        val sel = StringSelection(file.path)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }

    private fun canPaste(): Boolean {
        val sysClip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (sysClip.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return true
        return clipboardFile != null
    }

    private fun pasteClipboard() {
        val sysClip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (sysClip.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
            try {
                @Suppress("UNCHECKED_CAST")
                val files = sysClip.getData(DataFlavor.javaFileListFlavor) as? List<File>
                if (!files.isNullOrEmpty()) {
                    uploadLocalFiles(files, currentPath)
                    return
                }
            } catch (_: Throwable) {}
        }

        val item = clipboardFile
        if (item != null) {
            if (item.sessionId == session.id) {
                duplicateRemoteFile(item.file, currentPath)
            } else {
                notifyError("Cannot paste remote file across different sessions.")
            }
        }
    }

    private fun duplicateRemoteFile(source: RemoteFile, targetDir: String) {
        val targetName = if (normalizeRemotePath(targetDir, "") == normalizeRemotePath(source.path.substringBeforeLast('/', ""), "")) {
            val dot = source.name.lastIndexOf('.')
            if (dot > 0) "${source.name.substring(0, dot)}_copy${source.name.substring(dot)}" else "${source.name}_copy"
        } else {
            source.name
        }
        val targetPath = normalizeRemotePath(targetDir, targetName)

        object : Task.Backgroundable(project, "Copying ${source.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Copying ${source.name}..."
                val tempFile = File.createTempFile("haifassh_cp_", ".tmp")
                try {
                    fs.download(source.path, tempFile) { b, t ->
                        if (t > 0) indicator.fraction = (b.toDouble() / t.toDouble()) * 0.5
                    }
                    fs.upload(tempFile, targetDir) { b, t ->
                        if (t > 0) indicator.fraction = 0.5 + (b.toDouble() / t.toDouble()) * 0.5
                    }
                    val defaultDest = normalizeRemotePath(targetDir, tempFile.name)
                    if (defaultDest != targetPath) {
                        fs.rename(defaultDest, targetPath)
                    }
                } finally {
                    tempFile.delete()
                }
            }

            override fun onSuccess() {
                refreshCurrentDirectory()
            }

            override fun onThrowable(error: Throwable) {
                notifyError("Copy failed: ${error.message}")
            }
        }.queue()
    }

    private class RemoteFileTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Size", "Permissions", "Modified")
        private var files: List<RemoteFile> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

        fun setFiles(newFiles: List<RemoteFile>) {
            files = newFiles
            fireTableDataChanged()
        }

        fun getFileAt(row: Int): RemoteFile? = files.getOrNull(row)

        override fun getRowCount(): Int = files.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val file = files[rowIndex]
            return when (columnIndex) {
                0 -> file.name
                1 -> file.formattedSize()
                2 -> file.permissions
                3 -> if (file.lastModified > 0) dateFormat.format(Date(file.lastModified)) else "-"
                else -> ""
            }
        }
    }

    companion object {
        data class RemoteClipboardItem(val sessionId: String, val file: RemoteFile)
        var clipboardFile: RemoteClipboardItem? = null
    }
}
