package com.haifasoft.haifassh.fs.ui

import com.intellij.ui.TableSpeedSearch
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JTable

/**
 * Provides file explorer-like type-ahead search for file tables.
 * When a user types characters (e.g. "Down"), it searches file names,
 * automatically jumps row selection to the matching row (e.g. "Downloads"),
 * and scrolls it into view. Also installs IntelliJ's TableSpeedSearch.
 */
class FileTableTypeAhead(
    private val table: JTable,
    private val getFileName: (modelRow: Int) -> String?,
) : KeyAdapter() {

    private val buffer = StringBuilder()
    private var lastKeyTime = 0L

    companion object {
        const val TIMEOUT_MS = 1500L

        fun installOn(table: JTable, getFileName: (modelRow: Int) -> String?): FileTableTypeAhead {
            // Install TableSpeedSearch first so its listeners are registered before ours
            runCatching {
                TableSpeedSearch.installOn(table)
            }
            val listener = FileTableTypeAhead(table, getFileName)
            table.addKeyListener(listener)
            table.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    listener.clear()
                }
            })
            return listener
        }
    }

    private fun getSpeedSearch(): com.intellij.ui.speedSearch.SpeedSearchSupply? {
        return runCatching {
            com.intellij.ui.speedSearch.SpeedSearchSupply.getSupply(table, true)
                ?: com.intellij.ui.speedSearch.SpeedSearchSupply.getSupply(table)
        }.getOrNull()
    }

    fun hasActiveBuffer(): Boolean {
        val now = System.currentTimeMillis()
        return buffer.isNotEmpty() && (now - lastKeyTime <= TIMEOUT_MS)
    }

    fun getBuffer(): String = buffer.toString()

    fun clear() {
        buffer.setLength(0)
        lastKeyTime = 0L
    }

    override fun keyPressed(e: KeyEvent) {
        if (e.isConsumed) return

        val speedSearch = getSpeedSearch()
        if (speedSearch != null && speedSearch.isPopupActive) {
            // When IntelliJ's native TableSpeedSearch popup is active on screen,
            // let TableSpeedSearch handle Backspace/Escape and update its popup text.
            if (e.keyCode == KeyEvent.VK_BACK_SPACE && buffer.isNotEmpty()) {
                buffer.deleteCharAt(buffer.length - 1)
            }
            return
        }

        when (e.keyCode) {
            KeyEvent.VK_BACK_SPACE -> {
                val now = System.currentTimeMillis()
                if (now - lastKeyTime > TIMEOUT_MS) {
                    buffer.setLength(0)
                }

                if (buffer.isNotEmpty()) {
                    buffer.deleteCharAt(buffer.length - 1)
                    lastKeyTime = now
                    if (buffer.isNotEmpty()) {
                        performSearch(buffer.toString().lowercase(), isSingleCharCycle = false)
                    }
                }
                // ALWAYS consume Backspace on the file table when speed search popup is not active,
                // so it never triggers accidental parent directory navigation.
                e.consume()
            }
            KeyEvent.VK_ESCAPE -> {
                if (buffer.isNotEmpty()) {
                    clear()
                    e.consume()
                }
            }
        }
    }

    override fun keyTyped(e: KeyEvent) {
        if (e.isConsumed) return

        val ch = e.keyChar
        if (ch.isISOControl() || e.isMetaDown || e.isControlDown || e.isAltDown) return

        // Space only counts if already typing a multi-word name
        if (ch == ' ' && buffer.isEmpty()) return

        val speedSearch = getSpeedSearch()
        if (speedSearch != null && speedSearch.isPopupActive) {
            // SpeedSearch popup is already active and handling typing
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastKeyTime > TIMEOUT_MS) {
            buffer.setLength(0)
        }
        lastKeyTime = now
        buffer.append(ch)

        val query = buffer.toString().lowercase()
        // Check if single repeating character (e.g. typing 'd', 'd', 'd' to cycle through D-items)
        val isSingleCharCycle = query.length > 1 && query.all { it == query[0] }
        performSearch(query, isSingleCharCycle)
    }

    fun performSearch(query: String, isSingleCharCycle: Boolean = false) {
        val rowCount = table.rowCount
        if (rowCount == 0) return

        val searchPrefix = if (isSingleCharCycle) query.substring(0, 1) else query
        val currentRow = table.selectedRow.coerceAtLeast(0)
        val startIndex = if (isSingleCharCycle) (currentRow + 1) % rowCount else 0

        var targetRow = -1

        // 1. Try prefix search (starts with)
        for (i in 0 until rowCount) {
            val r = (startIndex + i) % rowCount
            val modelRow = table.convertRowIndexToModel(r)
            val name = getFileName(modelRow)?.lowercase() ?: continue
            if (name.startsWith(searchPrefix)) {
                targetRow = r
                break
            }
        }

        // 2. Fallback to contains search if not single char cycle
        if (targetRow == -1 && !isSingleCharCycle) {
            for (i in 0 until rowCount) {
                val r = (startIndex + i) % rowCount
                val modelRow = table.convertRowIndexToModel(r)
                val name = getFileName(modelRow)?.lowercase() ?: continue
                if (name.contains(searchPrefix)) {
                    targetRow = r
                    break
                }
            }
        }

        if (targetRow != -1) {
            table.setRowSelectionInterval(targetRow, targetRow)
            table.scrollRectToVisible(table.getCellRect(targetRow, 0, true))
        }
    }
}
