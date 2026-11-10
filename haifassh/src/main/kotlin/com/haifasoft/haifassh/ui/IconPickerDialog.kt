package com.haifasoft.haifassh.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JList

/**
 * A small dialog for choosing one [SessionIcons] icon — used to set the icon of
 * one or several selected sessions at once.
 */
class IconPickerDialog(project: Project, initialKey: String) : DialogWrapper(project) {

    private val combo = ComboBox(SessionIcons.choices.toTypedArray()).apply {
        selectedItem = SessionIcons.choice(initialKey)
        renderer = object : SimpleListCellRenderer<SessionIcons.Choice>() {
            override fun customize(
                list: JList<out SessionIcons.Choice>,
                value: SessionIcons.Choice?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.label ?: ""
                icon = value?.icon
            }
        }
    }

    init {
        title = "Choose Icon"
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Icon:", combo)
            .panel

    fun chosenKey(): String = (combo.selectedItem as? SessionIcons.Choice)?.key ?: SessionIcons.DEFAULT_KEY
}
