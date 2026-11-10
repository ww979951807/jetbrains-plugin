package com.haifasoft.haifassh.ssh

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.haifasoft.haifassh.model.SshAuthType
import com.haifasoft.haifassh.model.SshSession
import com.haifasoft.haifassh.settings.SshCredentialStore

object SshConnectionHelper {

    fun buildChain(project: Project, session: SshSession): List<HopConfig>? {
        val chain = ArrayList<HopConfig>(session.jumpHosts.size + 1)
        session.jumpHosts.forEachIndexed { index, jump ->
            val hop = resolveHop(
                project = project,
                label = "jump host ${jump.label()}",
                host = jump.host,
                port = jump.port,
                username = jump.username,
                authType = jump.authType,
                keyPath = jump.privateKeyPath,
                password = SshCredentialStore.getJumpPassword(session.id, index),
                passphrase = SshCredentialStore.getJumpPassphrase(session.id, index),
                allowLegacy = session.allowLegacyAlgorithms,
            ) ?: return null
            chain.add(hop)
        }

        val target = resolveHop(
            project = project,
            label = session.displayName(),
            host = session.host,
            port = session.port,
            username = session.username,
            authType = session.authType,
            keyPath = session.privateKeyPath,
            password = SshCredentialStore.getPassword(session.id),
            passphrase = SshCredentialStore.getPassphrase(session.id),
            allowLegacy = session.allowLegacyAlgorithms,
        ) ?: return null
        chain.add(target)
        return chain
    }

    private fun resolveHop(
        project: Project,
        label: String,
        host: String,
        port: Int,
        username: String,
        authType: SshAuthType,
        keyPath: String,
        password: String?,
        passphrase: String?,
        allowLegacy: Boolean,
    ): HopConfig? {
        var resolvedPassword = password
        if (authType == SshAuthType.PASSWORD && resolvedPassword.isNullOrEmpty()) {
            resolvedPassword = Messages.showPasswordDialog(
                project,
                "Password for $label:",
                "SSH Authentication",
                null,
            ) ?: return null
        }
        return HopConfig(
            host,
            port,
            username,
            authType,
            keyPath,
            resolvedPassword?.toByteArray(Charsets.UTF_8),
            passphrase?.toByteArray(Charsets.UTF_8),
            allowLegacy,
        )
    }

    fun hostKeyPrompt(project: Project): SshConnector.HostKeyPrompt = object : SshConnector.HostKeyPrompt {
        override fun confirm(message: String): Boolean {
            var trust = false
            ApplicationManager.getApplication().invokeAndWait {
                trust = Messages.showYesNoDialog(
                    project,
                    message,
                    "SSH Host Key Verification",
                    "Connect",
                    "Abort",
                    Messages.getWarningIcon(),
                ) == Messages.YES
            }
            return trust
        }

        override fun notify(message: String) {
            ApplicationManager.getApplication().invokeAndWait {
                Messages.showWarningDialog(project, message, "SSH Host Key Warning")
            }
        }
    }
}
