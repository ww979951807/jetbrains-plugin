package com.haifasoft.haifassh.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JList

/** Scope a saved command belongs to. */
enum class SnippetScope { GLOBAL, SESSION }

/** Result of editing a snippet in [CommandSnippetDialog]. */
class SnippetEdit(
    var description: String = "",
    var command: String = "",
    var scope: SnippetScope = SnippetScope.GLOBAL,
)

/**
 * Add/edit dialog for a saved command. The command is typed verbatim into the
 * shell (never executed), so it is edited as free multi-line text. When
 * [allowSession] is false (no active session context) the scope chooser is
 * hidden and the snippet is global.
 */
class CommandSnippetDialog(
    project: Project?,
    private val edit: SnippetEdit,
    isNew: Boolean,
    private val allowSession: Boolean,
    private val sessionName: String,
) : DialogWrapper(project) {

    private val descriptionField = JBTextField(edit.description)
    private val commandArea = JBTextArea(edit.command, 4, 40).apply {
        lineWrap = true
        wrapStyleWord = false
    }
    private val scopeCombo = ComboBox(arrayOf(SnippetScope.GLOBAL, SnippetScope.SESSION)).apply {
        selectedItem = edit.scope
        renderer = object : SimpleListCellRenderer<SnippetScope>() {
            override fun customize(
                list: JList<out SnippetScope>,
                value: SnippetScope?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.let { scopeLabel(it, sessionName) } ?: ""
            }
        }
    }

    init {
        title = if (isNew) "Add Command" else "Edit Command"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Description:", descriptionField)
            .addLabeledComponentFillVertically("Command:", commandArea.withScroll())
        if (allowSession) builder.addLabeledComponent("Save to:", scopeCombo)
        return builder.panel.apply { preferredSize = JBUI.size(460, 220) }
    }

    override fun getPreferredFocusedComponent(): JComponent = descriptionField

    override fun doValidate(): ValidationInfo? {
        if (descriptionField.text.isBlank()) return ValidationInfo("Description is required", descriptionField)
        if (commandArea.text.isBlank()) return ValidationInfo("Command is required", commandArea)
        return null
    }

    override fun doOKAction() {
        edit.description = descriptionField.text.trim()
        // Preserve the command's internal whitespace; only trim trailing newlines
        // so a stray Enter in the editor never turns into an execution.
        edit.command = commandArea.text.trimEnd('\n', '\r')
        edit.scope = if (allowSession) scopeCombo.selectedItem as SnippetScope else SnippetScope.GLOBAL
        super.doOKAction()
    }

    private fun JComponent.withScroll(): JComponent =
        com.intellij.ui.components.JBScrollPane(this)

    companion object {
        fun scopeLabel(scope: SnippetScope, sessionName: String): String = when (scope) {
            SnippetScope.GLOBAL -> "Global library"
            SnippetScope.SESSION -> "This session${if (sessionName.isNotBlank()) " ($sessionName)" else ""}"
        }
    }
}
