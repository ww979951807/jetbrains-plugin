package com.haifasoft.haifassh.fs

import com.haifasoft.haifassh.fs.ui.FileTableTypeAhead
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.event.KeyEvent
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class FileTableTypeAheadTest {

    @Test
    fun testTypeAheadFindsDownloads() {
        val fileNames = listOf("Applications", "Desktop", "Documents", "Downloads", "Music", "Pictures")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Simulate typing 'D', 'o', 'w', 'n'
        for (char in "Down") {
            val event = KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)
            listener.keyTyped(event)
        }

        assertEquals("Downloads row index should be selected", 3, table.selectedRow)
        assertEquals("Downloads", model.getValueAt(table.selectedRow, 0))
    }

    @Test
    fun testCaseInsensitiveSearch() {
        val fileNames = listOf("Archive.tar", "Build", "config.json", "docker-compose.yml")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Simulate typing "DOCK" in uppercase
        for (char in "DOCK") {
            val event = KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)
            listener.keyTyped(event)
        }

        assertEquals(3, table.selectedRow)
        assertEquals("docker-compose.yml", model.getValueAt(table.selectedRow, 0))
    }

    @Test
    fun testSingleCharacterCycle() {
        val fileNames = listOf("alpha", "beta", "delta1", "delta2", "delta3", "gamma")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Type 'd' -> should select delta1 (index 2)
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'd'))
        assertEquals(2, table.selectedRow)

        // Type 'd' again immediately -> should cycle to delta2 (index 3)
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'd'))
        assertEquals(3, table.selectedRow)

        // Type 'd' again immediately -> should cycle to delta3 (index 4)
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'd'))
        assertEquals(4, table.selectedRow)
    }

    @Test
    fun testBackspaceDeletesCharactersFromBufferAndConsumesEvent() {
        val fileNames = listOf("Applications", "Desktop", "Documents", "Downloads")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // Type "Down"
        for (char in "Down") {
            listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char))
        }
        assertEquals("Down", listener.getBuffer())
        assertEquals(3, table.selectedRow) // Downloads

        // Press Backspace once -> buffer becomes "Dow"
        val backspaceEvent1 = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(backspaceEvent1)
        org.junit.Assert.assertTrue("Backspace event must be consumed", backspaceEvent1.isConsumed)
        assertEquals("Dow", listener.getBuffer())
        assertEquals(3, table.selectedRow)

        // Press Backspace again -> buffer becomes "Do"
        val backspaceEvent2 = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(backspaceEvent2)
        assertEquals("Do", listener.getBuffer())

        // Press Backspace again -> buffer becomes "D"
        val backspaceEvent3 = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(backspaceEvent3)
        assertEquals("D", listener.getBuffer())

        // Press Backspace again -> buffer becomes empty ""
        val backspaceEvent4 = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(backspaceEvent4)
        assertEquals("", listener.getBuffer())

        // Press Backspace again when buffer is already empty -> still consumed, no error
        val backspaceEvent5 = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(backspaceEvent5)
        org.junit.Assert.assertTrue("Backspace on empty buffer must be consumed to prevent parent navigation", backspaceEvent5.isConsumed)
        assertEquals("", listener.getBuffer())
    }

    @Test
    fun testTypoCorrectionWithBackspace() {
        val fileNames = listOf("Desktop", "Documents", "Downloads", "Pictures")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        // User intends to type "Down" but types "Dowz" (typo)
        for (char in "Dowz") {
            listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char))
        }
        assertEquals("Dowz", listener.getBuffer())

        // Hit Backspace to delete 'z' -> buffer is "Dow", selection jumps to Downloads (index 2)
        val backspaceEvent = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(backspaceEvent)
        assertEquals("Dow", listener.getBuffer())
        assertEquals("Downloads should be selected after deleting typo", 2, table.selectedRow)

        // Now type 'n' -> buffer is "Down", still Downloads
        listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'n'))
        assertEquals("Down", listener.getBuffer())
        assertEquals(2, table.selectedRow)
    }

    @Test
    fun testEscapeClearsBuffer() {
        val fileNames = listOf("Desktop", "Downloads")
        val model = DefaultTableModel(arrayOf("Name"), 0)
        fileNames.forEach { model.addRow(arrayOf(it)) }

        val table = JTable(model)
        val listener = FileTableTypeAhead(table) { row -> model.getValueAt(row, 0) as? String }

        for (char in "Down") {
            listener.keyTyped(KeyEvent(table, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char))
        }
        assertEquals("Down", listener.getBuffer())

        val escapeEvent = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
        listener.keyPressed(escapeEvent)
        org.junit.Assert.assertTrue(escapeEvent.isConsumed)
        assertEquals("", listener.getBuffer())
    }
}
