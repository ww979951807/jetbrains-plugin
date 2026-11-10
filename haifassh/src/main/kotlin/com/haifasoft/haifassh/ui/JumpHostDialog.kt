package com.haifasoft.haifassh.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.haifasoft.haifassh.model.JumpHost
import com.haifasoft.haifassh.model.SshAuthType
import javax.swing.JComponent
import javax.swing.JList

/** A jump host together with its (in-memory) secrets while being edited. */
class JumpHostEntry(
    val host: JumpHost = JumpHost(),
    var password: String? = null,
    var passphrase: String? = null,
) {
    fun label(): String = host.label()
}

/** Editor for a single jump host. Mutates [entry] in place on OK. */
class JumpHostDialog(
    project: Project,
    private val entry: JumpHostEntry,
    isNew: Boolean,
) : DialogWrapper(project) {

    private val hostField = JBTextField(entry.host.host)
    private val portField = JBIntSpinner(entry.host.port.coerceIn(1, 65535), 1, 65535)
    private val usernameField = JBTextField(entry.host.username)
    private val authCombo = ComboBox(arrayOf(SshAuthType.PASSWORD, SshAuthType.KEY)).apply {
        selectedItem = entry.host.authType
        renderer = object : SimpleListCellRenderer<SshAuthType>() {
            override fun customize(
                list: JList<out SshAuthType>,
                value: SshAuthType?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.let { authLabel(it) } ?: ""
            }
        }
    }
    private val keyPathField = TextFieldWithBrowseButton().apply { text = entry.host.privateKeyPath }
    private val passwordField = JBPasswordField().apply { text = entry.password ?: "" }
    private val passphraseField = JBPasswordField().apply { text = entry.passphrase ?: "" }

    init {
        title = if (isNew) "Add Jump Host" else "Edit Jump Host"
        installFileBrowser(
            keyPathField,
            project,
            "Select Private Key",
            "Choose the private key file for this jump host",
        )
        authCombo.addActionListener { updateAuthFields() }
        init()
        updateAuthFields()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Host:", hostField)
            .addLabeledComponent("Port:", portField)
            .addLabeledComponent("Username:", usernameField)
            .addLabeledComponent("Authentication:", authCombo)
            .addLabeledComponent("Password:", passwordField)
            .addLabeledComponent("Private key:", keyPathField)
            .addLabeledComponent("Passphrase:", passphraseField)
            .panel

    private fun updateAuthFields() {
        val key = authCombo.selectedItem == SshAuthType.KEY
        passwordField.isEnabled = !key
        keyPathField.isEnabled = key
        passphraseField.isEnabled = key
    }

    override fun doValidate(): ValidationInfo? {
        if (hostField.text.isBlank()) return ValidationInfo("Host is required", hostField)
        if (usernameField.text.isBlank()) return ValidationInfo("Username is required", usernameField)
        if (authCombo.selectedItem == SshAuthType.KEY && keyPathField.text.isBlank()) {
            return ValidationInfo("Private key path is required for key authentication", keyPathField)
        }
        return null
    }

    override fun doOKAction() {
        entry.host.host = hostField.text.trim()
        entry.host.port = portField.number
        entry.host.username = usernameField.text.trim()
        entry.host.authType = authCombo.selectedItem as SshAuthType
        entry.host.privateKeyPath = keyPathField.text.trim()
        val pw = passwordField.password
        val pp = passphraseField.password
        try {
            entry.password = String(pw).ifEmpty { null }
            entry.passphrase = String(pp).ifEmpty { null }
        } finally {
            pw.fill(' ')
            pp.fill(' ')
        }
        super.doOKAction()
    }

    companion object {
        fun authLabel(type: SshAuthType): String = when (type) {
            SshAuthType.PASSWORD -> "Password"
            SshAuthType.KEY -> "Private key"
        }
    }
}
