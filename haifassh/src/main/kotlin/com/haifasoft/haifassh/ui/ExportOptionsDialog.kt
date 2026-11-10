package com.haifasoft.haifassh.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Asks how many sessions to export and whether to include credentials. Metadata
 * is always exported; including secrets is opt-in and, when chosen, requires a
 * passphrase — the bundle is then encrypted (see
 * [com.haifasoft.haifassh.settings.ExportCrypto]) so it never holds plaintext
 * secrets on disk.
 */
class ExportOptionsDialog(project: Project, private val sessionCount: Int) : DialogWrapper(project) {

    private val includeSecretsCheck = JBCheckBox("Include saved passwords & passphrases", false)
    private val passphraseField = JBPasswordField()
    private val confirmField = JBPasswordField()

    init {
        title = "Export SSH Sessions"
        includeSecretsCheck.addActionListener { updatePassphraseEnabled() }
        init()
        updatePassphraseEnabled()
    }

    override fun createCenterPanel(): JComponent {
        val info = JBLabel(
            "<html><body style='width:340px'>Including credentials encrypts the export with the " +
                "passphrase below (AES-256). You'll need the same passphrase to import it; if you " +
                "lose it, the secrets can't be recovered. Leave unchecked to export configuration only.</body></html>",
        ).apply { border = JBUI.Borders.emptyTop(6) }

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Exporting $sessionCount session(s)."))
            .addComponent(includeSecretsCheck)
            .addLabeledComponent("Passphrase:", passphraseField)
            .addLabeledComponent("Confirm passphrase:", confirmField)
            .addComponent(info)
            .panel
    }

    private fun updatePassphraseEnabled() {
        val on = includeSecretsCheck.isSelected
        passphraseField.isEnabled = on
        confirmField.isEnabled = on
    }

    override fun doValidate(): ValidationInfo? {
        if (!includeSecretsCheck.isSelected) return null
        if (passphraseField.password.isEmpty()) {
            return ValidationInfo("Enter a passphrase to encrypt the credentials", passphraseField)
        }
        if (!passphraseField.password.contentEquals(confirmField.password)) {
            return ValidationInfo("Passphrases do not match", confirmField)
        }
        return null
    }

    val includeSecrets: Boolean
        get() = includeSecretsCheck.isSelected

    /** The encryption passphrase, or null for a config-only export. */
    val passphrase: CharArray?
        get() = if (includeSecretsCheck.isSelected) passphraseField.password else null
}
