package com.haifasoft.haifassh.fs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.haifasoft.haifassh.model.SshSession
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

enum class LocalViewMode(val title: String) {
    DOCKED_PINNED("Docked Pinned"),
    DOCKED_UNPINNED("Docked Unpinned"),
    WINDOW("Window Mode"),
    FLOAT("Float"),
    UNDOCK("Undock");

    val isDocked: Boolean get() = this == DOCKED_PINNED || this == DOCKED_UNPINNED
}

enum class DockPosition(val title: String) {
    LEFT("Move Left"),
    RIGHT("Move Right"),
    UP("Move Up"),
    DOWN("Move Down"),
}

/**
 * Embedded or detached Local File Explorer panel with multi-select, drag-and-drop,
 * and IntelliJ terminal-like View Modes (Docked Pinned, Docked Unpinned, Window Mode, Float, Undock)
 * and Move Options (Move Left, Right, Up, Down).
 */
class LocalFileExplorerPanel(
    private val project: Project,
    private val session: SshSession,
    private val onUploadFiles: (List<File>) -> Unit,
    private val onViewModeChanged: ((LocalViewMode) -> Unit)? = null,
    private val onPositionChanged: ((DockPosition) -> Unit)? = null,
    private val onCloseRequested: (() -> Unit)? = null,
) : JBPanel<LocalFileExplorerPanel>(BorderLayout()) {

    var currentViewMode: LocalViewMode = LocalViewMode.DOCKED_PINNED
        private set

    var currentPosition: DockPosition = DockPosition.LEFT
        private set

    private var currentDir: File = initialDirectory()
    private val history = mutableListOf<File>()
    private var historyIndex = -1
    private var fileList = listOf<File>()

    private val titleLabel = JBLabel("Local Files", AllIcons.Nodes.Folder, JBLabel.LEFT)
    private val pathField = JBTextField()
    private val statusLabel = JBLabel("Loading...")
    private val selectionLabel = JBLabel("")
    private val tableModel = LocalFileTableModel()
    private val table = JBTable(tableModel)
    private var typeAhead: FileTableTypeAhead? = null

    private val dockToggleBtn = HoverIconButton("Detach to Window", AllIcons.Actions.MoveToWindow) {
        val nextMode = if (currentViewMode.isDocked) LocalViewMode.WINDOW else LocalViewMode.DOCKED_PINNED
        setViewMode(nextMode)
    }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 1, 1, 1),
            JBUI.Borders.empty(),
        )

        add(buildHeaderPanel(), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)

        setupTable()
        navigateTo(currentDir, addToHistory = true)
    }

    fun setViewMode(mode: LocalViewMode) {
        currentViewMode = mode
        updateHeaderButtons()
        onViewModeChanged?.invoke(mode)
    }

    fun setDockPosition(pos: DockPosition) {
        currentPosition = pos
        onPositionChanged?.invoke(pos)
    }

    fun updateHeaderButtons() {
        if (currentViewMode.isDocked) {
            dockToggleBtn.icon = AllIcons.Actions.MoveToWindow
            dockToggleBtn.toolTipText = "Detach to Window / Float"
            dockToggleBtn.repaint()
        } else {
            dockToggleBtn.icon = AllIcons.Actions.PinTab
            dockToggleBtn.toolTipText = "Put back to dock (Side-by-Side)"
            dockToggleBtn.repaint()
        }
    }

    private fun initialDirectory(): File {
        val projectPath = project.basePath?.let { File(it) }
        if (projectPath != null && projectPath.exists() && projectPath.isDirectory) {
            return projectPath
        }
        val userHome = File(System.getProperty("user.home"))
        if (userHome.exists() && userHome.isDirectory) {
            return userHome
        }
        return File(".").canonicalFile
    }

    private fun buildHeaderPanel(): JPanel {
        val mainTop = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 0, 0, 1, 0),
                JBUI.Borders.empty(2, 4),
            )
        }

        // Title and View Mode actions row
        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 4, 4)
        }
        val titleBox = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            titleLabel.font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
            add(titleLabel)
        }

        lateinit var gearBtn: HoverIconButton
        val controlsBox = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(dockToggleBtn)
            gearBtn = HoverIconButton("View Options", AllIcons.General.GearPlain) {
                showViewOptionsMenu(gearBtn)
            }
            add(gearBtn)
            add(HoverIconButton("Close Local Files", AllIcons.Actions.Close) {
                onCloseRequested?.invoke()
            })
        }

        headerRow.add(titleBox, BorderLayout.WEST)
        headerRow.add(controlsBox, BorderLayout.EAST)

        // Address bar
        val addressBar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 4, 4)
        }
        pathField.addActionListener {
            val input = pathField.text.trim()
            val target = File(input)
            if (target.exists() && target.isDirectory) {
                navigateTo(target, addToHistory = true)
            } else if (target.exists() && target.isFile) {
                target.parentFile?.let { navigateTo(it, addToHistory = true) }
            }
        }
        val goBtn = HoverIconButton("Go to path (Enter)", AllIcons.Actions.Forward) {
            val input = pathField.text.trim()
            val target = File(input)
            if (target.exists() && target.isDirectory) {
                navigateTo(target, addToHistory = true)
            }
        }
        addressBar.add(pathField, BorderLayout.CENTER)
        addressBar.add(goBtn, BorderLayout.EAST)

        // Icon toolbar
        val iconBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 2, 2, 2)
        }
        val leftIcons = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(HoverIconButton("Back (Alt+Left)", AllIcons.Actions.Back) { navigateBack() })
            add(HoverIconButton("Forward (Alt+Right)", AllIcons.Actions.Forward) { navigateForward() })
            add(HoverIconButton("Up Folder (Alt+Up)", AllIcons.Nodes.UpFolder) { navigateUp() })
            add(HoverIconButton("Refresh (F5)", AllIcons.Actions.Refresh) { refreshCurrentDirectory() })

            add(createVerticalSeparator())

            val userHome = File(System.getProperty("user.home"))
            add(HoverIconButton("User Home (${userHome.name})", AllIcons.Nodes.HomeFolder) {
                navigateTo(userHome, addToHistory = true)
            })

            project.basePath?.let { p ->
                val pFile = File(p)
                if (pFile.exists()) {
                    add(HoverIconButton("Project Root (${pFile.name})", AllIcons.Nodes.Folder) {
                        navigateTo(pFile, addToHistory = true)
                    })
                }
            }

            val desktop = File(userHome, "Desktop")
            if (desktop.exists()) {
                add(HoverIconButton("Desktop", AllIcons.Nodes.Desktop) {
                    navigateTo(desktop, addToHistory = true)
                })
            }
        }

        val rightIcons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(HoverIconButton("Upload Selected to Session (${session.displayName()})", AllIcons.Actions.Upload) {
                uploadSelectedFiles()
            })
            add(HoverIconButton("Select All", AllIcons.Actions.Selectall) {
                if (table.rowCount > 0) {
                    table.setRowSelectionInterval(0, table.rowCount - 1)
                }
            })
        }

        iconBar.add(leftIcons, BorderLayout.WEST)
        iconBar.add(rightIcons, BorderLayout.EAST)

        mainTop.add(headerRow, BorderLayout.NORTH)
        val middle = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(addressBar, BorderLayout.NORTH)
            add(iconBar, BorderLayout.SOUTH)
        }
        mainTop.add(middle, BorderLayout.CENTER)

        return mainTop
    }

    private fun showViewOptionsMenu(invoker: JComponent) {
        val menu = JPopupMenu()

        val viewHeader = JMenuItem("View Mode").apply { isEnabled = false }
        menu.add(viewHeader)

        menu.add(JRadioButtonMenuItem("Docked Pinned", currentViewMode == LocalViewMode.DOCKED_PINNED).apply {
            addActionListener { setViewMode(LocalViewMode.DOCKED_PINNED) }
        })
        menu.add(JRadioButtonMenuItem("Docked Unpinned", currentViewMode == LocalViewMode.DOCKED_UNPINNED).apply {
            addActionListener { setViewMode(LocalViewMode.DOCKED_UNPINNED) }
        })
        menu.add(JRadioButtonMenuItem("Window Mode", currentViewMode == LocalViewMode.WINDOW).apply {
            addActionListener { setViewMode(LocalViewMode.WINDOW) }
        })
        menu.add(JRadioButtonMenuItem("Float", currentViewMode == LocalViewMode.FLOAT).apply {
            addActionListener { setViewMode(LocalViewMode.FLOAT) }
        })
        menu.add(JRadioButtonMenuItem("Undock", currentViewMode == LocalViewMode.UNDOCK).apply {
            addActionListener { setViewMode(LocalViewMode.UNDOCK) }
        })

        menu.addSeparator()

        val moveHeader = JMenuItem("Move To").apply { isEnabled = false }
        menu.add(moveHeader)

        menu.add(JMenuItem("Move Left" + if (currentPosition == DockPosition.LEFT && currentViewMode.isDocked) "  ✓" else "").apply {
            addActionListener {
                if (!currentViewMode.isDocked) setViewMode(LocalViewMode.DOCKED_PINNED)
                setDockPosition(DockPosition.LEFT)
            }
        })
        menu.add(JMenuItem("Move Right" + if (currentPosition == DockPosition.RIGHT && currentViewMode.isDocked) "  ✓" else "").apply {
            addActionListener {
                if (!currentViewMode.isDocked) setViewMode(LocalViewMode.DOCKED_PINNED)
                setDockPosition(DockPosition.RIGHT)
            }
        })
        menu.add(JMenuItem("Move Up" + if (currentPosition == DockPosition.UP && currentViewMode.isDocked) "  ✓" else "").apply {
            addActionListener {
                if (!currentViewMode.isDocked) setViewMode(LocalViewMode.DOCKED_PINNED)
                setDockPosition(DockPosition.UP)
            }
        })
        menu.add(JMenuItem("Move Down" + if (currentPosition == DockPosition.DOWN && currentViewMode.isDocked) "  ✓" else "").apply {
            addActionListener {
                if (!currentViewMode.isDocked) setViewMode(LocalViewMode.DOCKED_PINNED)
                setDockPosition(DockPosition.DOWN)
            }
        })

        menu.addSeparator()
        menu.add(JMenuItem("Close Local Files", AllIcons.Actions.Close).apply {
            addActionListener { onCloseRequested?.invoke() }
        })

        menu.show(invoker, 0, invoker.height)
    }

    private fun createVerticalSeparator(): JSeparator = JSeparator(SwingConstants.VERTICAL).apply {
        preferredSize = Dimension(JBUI.scale(6), JBUI.scale(20))
        foreground = UIUtil.getBoundsColor()
    }

    private fun buildStatusBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 8),
            )
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(statusLabel)
                add(selectionLabel.apply { foreground = JBColor.namedColor("HaifaSSH.Accent", JBColor(0x2E7D32, 0x81C784)) })
            }
            val hintLabel = JBLabel("💡 Drag to remote to upload").apply {
                foreground = JBColor.GRAY
            }
            add(leftPanel, BorderLayout.WEST)
            add(hintLabel, BorderLayout.EAST)
        }
    }

    private fun setupTable() {
        // Multi-select enabled
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.autoCreateRowSorter = true
        table.rowHeight = JBUI.scale(22)
        table.setShowGrid(false)

        // Install SpeedSearch & File Explorer Type-ahead navigation (typing "Down" jumps to Downloads)
        typeAhead = FileTableTypeAhead.installOn(table) { modelRow ->
            tableModel.getFileAt(modelRow)?.name
        }

        // Drag and drop export of javaFileListFlavor
        table.dragEnabled = true
        table.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent?): Int = DnDConstants.ACTION_COPY_OR_MOVE

            override fun createTransferable(c: JComponent?): Transferable? {
                val selected = getSelectedFiles()
                if (selected.isEmpty()) return null
                return object : Transferable {
                    override fun getTransferDataFlavors(): Array<DataFlavor> =
                        arrayOf(DataFlavor.javaFileListFlavor)

                    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
                        flavor == DataFlavor.javaFileListFlavor

                    override fun getTransferData(flavor: DataFlavor?): Any {
                        if (flavor == DataFlavor.javaFileListFlavor) {
                            return selected
                        }
                        throw UnsupportedFlavorException(flavor)
                    }
                }
            }
        }

        // Custom renderer for file name and icon
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
                    text = file.name.ifEmpty { file.path }
                    icon = when {
                        file.isDirectory -> AllIcons.Nodes.Folder
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
                            navigateTo(file, addToHistory = true)
                        } else {
                            uploadSelectedFiles()
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

        table.selectionModel.addListSelectionListener {
            updateSelectionStatus()
        }

        // Keyboard navigation
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isConsumed) return
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val selected = getSelectedFiles()
                        if (selected.size == 1 && selected[0].isDirectory) {
                            navigateTo(selected[0], addToHistory = true)
                            e.consume()
                        } else if (selected.isNotEmpty()) {
                            uploadSelectedFiles()
                            e.consume()
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
                }
            }
        })
    }

    private fun getSelectedFiles(): List<File> {
        val rows = table.selectedRows
        if (rows.isEmpty()) return emptyList()
        return rows.map { row ->
            val modelRow = table.convertRowIndexToModel(row)
            tableModel.getFileAt(modelRow)
        }.filterNotNull()
    }

    private fun updateSelectionStatus() {
        val selected = getSelectedFiles()
        if (selected.isEmpty()) {
            selectionLabel.text = ""
        } else {
            val totalBytes = selected.sumOf { if (it.isFile) it.length() else 0L }
            val dirCount = selected.count { it.isDirectory }
            val fileCount = selected.size - dirCount
            val sizeStr = if (totalBytes > 0) " (${formatSize(totalBytes)})" else ""
            val detail = if (dirCount > 0 && fileCount > 0) {
                "${selected.size} selected ($dirCount folders, $fileCount files$sizeStr)"
            } else {
                "${selected.size} selected$sizeStr"
            }
            selectionLabel.text = detail
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
        }
        val selected = getSelectedFiles()

        val menu = JPopupMenu()
        menu.add(JMenuItem("Upload to Remote Session (${session.displayName()})", AllIcons.Actions.Upload).apply {
            isEnabled = selected.isNotEmpty()
            addActionListener { uploadSelectedFiles() }
        })
        menu.addSeparator()

        if (selected.size == 1) {
            val file = selected[0]
            if (file.isDirectory) {
                menu.add(JMenuItem("Open Folder", AllIcons.Nodes.Folder).apply {
                    addActionListener { navigateTo(file, addToHistory = true) }
                })
            } else {
                menu.add(JMenuItem("Open Locally", AllIcons.Actions.Edit).apply {
                    addActionListener {
                        runCatching { Desktop.getDesktop().open(file) }
                    }
                })
            }
            menu.add(JMenuItem("Copy Local Path", AllIcons.Actions.Copy).apply {
                addActionListener {
                    val sel = StringSelection(file.absolutePath)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        } else if (selected.size > 1) {
            menu.add(JMenuItem("Copy Paths (${selected.size} files)", AllIcons.Actions.Copy).apply {
                addActionListener {
                    val paths = selected.joinToString("\n") { it.absolutePath }
                    val sel = StringSelection(paths)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            })
        }

        menu.add(JMenuItem("Select All", AllIcons.Actions.Selectall).apply {
            addActionListener {
                if (table.rowCount > 0) {
                    table.setRowSelectionInterval(0, table.rowCount - 1)
                }
            }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Refresh", AllIcons.Actions.Refresh).apply {
            addActionListener { refreshCurrentDirectory() }
        })

        menu.show(e.component, e.x, e.y)
    }

    fun navigateTo(dir: File, addToHistory: Boolean = false) {
        typeAhead?.clear()
        val canonical = runCatching { dir.canonicalFile }.getOrDefault(dir)
        if (!canonical.exists() || !canonical.isDirectory) return

        currentDir = canonical
        pathField.text = canonical.absolutePath
        titleLabel.text = "Local Files — ${canonical.name.ifEmpty { canonical.path }}"

        val files = runCatching {
            canonical.listFiles()?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

        fileList = files.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        tableModel.setFiles(fileList)

        if (addToHistory) {
            if (historyIndex < history.size - 1) {
                history.subList(historyIndex + 1, history.size).clear()
            }
            history.add(canonical)
            historyIndex = history.size - 1
        }

        val dirCount = fileList.count { it.isDirectory }
        val fileCount = fileList.size - dirCount
        statusLabel.text = "${fileList.size} items ($dirCount folders, $fileCount files)"
        updateSelectionStatus()
    }

    private fun navigateUp() {
        val parent = currentDir.parentFile ?: return
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
        navigateTo(currentDir, addToHistory = false)
    }

    private fun uploadSelectedFiles() {
        val selected = getSelectedFiles()
        if (selected.isNotEmpty()) {
            onUploadFiles(selected)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    private inner class LocalFileTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Size", "Type", "Modified")
        private var files: List<File> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

        fun setFiles(newFiles: List<File>) {
            files = newFiles
            fireTableDataChanged()
        }

        fun getFileAt(row: Int): File? = files.getOrNull(row)

        override fun getRowCount(): Int = files.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val file = files[rowIndex]
            return when (columnIndex) {
                0 -> file.name.ifEmpty { file.path }
                1 -> if (file.isDirectory) "-" else formatSize(file.length())
                2 -> if (file.isDirectory) "Folder" else (file.extension.uppercase().ifEmpty { "File" })
                3 -> {
                    val lm = file.lastModified()
                    if (lm > 0) dateFormat.format(Date(lm)) else "-"
                }
                else -> ""
            }
        }
    }
}
