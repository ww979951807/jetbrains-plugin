package com.haifasoft.haifassh.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/**
 * Text-only list renderer. Stands in for `SimpleListCellRenderer.create(nullValue, getter)`,
 * which is scheduled for removal; subclassing [SimpleListCellRenderer] works on every
 * supported build (242+).
 */
fun <T> textRenderer(nullText: String = "", textOf: (T) -> String): SimpleListCellRenderer<T> =
    object : SimpleListCellRenderer<T>() {
        override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = if (value == null) nullText else textOf(value)
        }
    }

/**
 * Wires [field]'s browse button to a single-file chooser. The 4-arg
 * `addBrowseFolderListener` is scheduled for removal, and its replacement
 * `addBrowseFolderListener(project, descriptor)` only exists in 2024.3+. Since we
 * support 2024.2 (since-build 242), the button drives [FileChooser] directly.
 */
fun installFileBrowser(field: TextFieldWithBrowseButton, project: Project?, title: String, description: String) {
    field.addActionListener {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle(title)
            .withDescription(description)
        val current = LocalFileSystem.getInstance().findFileByPath(field.text.trim())
        FileChooser.chooseFile(descriptor, project, current)?.let { field.text = it.path }
    }
}
