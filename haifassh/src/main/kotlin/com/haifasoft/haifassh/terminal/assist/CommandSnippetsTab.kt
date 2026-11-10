package com.haifasoft.haifassh.terminal.assist

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.model.CommandSnippet
import com.haifasoft.haifassh.settings.SshCommandSnippetService
import com.haifasoft.haifassh.ui.CommandSnippetDialog
import com.haifasoft.haifassh.ui.SnippetEdit
import com.haifasoft.haifassh.ui.SnippetScope
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * "Commands" tab: a searchable library of saved commands, split into this
 * session's own list and the shared global library. Double-clicking a command
 * types it into the shell WITHOUT executing it (no trailing newline). Add/Edit/
 * Delete live in the toolbar; search matches both description and command text.
 */
class CommandSnippetsTab(
    private val project: Project?,
    private val sessionId: String,
    private val sessionName: String,
    typeText: (String) -> Unit,
    parentDisposable: Disposable,
) : JBPanel<CommandSnippetsTab>(BorderLayout()) {

    /** What a row points at, so Edit/Delete know the scope + id to act on. */
    private class SnippetRef(val scope: SnippetScope, val snippet: CommandSnippet)

    private val service = SshCommandSnippetService.getInstance()
    private val search = SearchTextField()
    private val rows = CommandRowList(typeText)

    init {
        add(buildHeader(), BorderLayout.NORTH)
        add(rows, BorderLayout.CENTER)

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })

        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(SshCommandSnippetService.TOPIC, SshCommandSnippetService.Listener {
                ApplicationManager.getApplication().invokeLater { refresh() }
            })

        refresh()
    }

    private fun buildHeader(): JPanel {
        val group = DefaultActionGroup().apply {
            add(AddAction())
            add(EditAction())
            add(DeleteAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
            .apply { targetComponent = rows }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(toolbar.component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }
    }

    private fun refresh() {
        val query = search.text.trim().lowercase()
        val out = ArrayList<CommandRowList.Row>()
        var total = 0
        var shownCount = 0

        fun section(title: String, scope: SnippetScope, snippets: List<CommandSnippet>) {
            val matches = snippets.filter { matches(it, query) }
            total += matches.size
            if (matches.isEmpty()) return
            out.add(CommandRowList.Row(primary = title, header = true))
            matches.take(MAX_ROWS - shownCount).forEach {
                out.add(
                    CommandRowList.Row(
                        primary = it.description,
                        secondary = it.command,
                        command = it.command,
                        userData = SnippetRef(scope, it),
                    ),
                )
                shownCount++
            }
        }

        if (sessionId.isNotBlank()) {
            section("This session${if (sessionName.isNotBlank()) " — $sessionName" else ""}",
                SnippetScope.SESSION, service.forSession(sessionId))
        }
        section("Global library", SnippetScope.GLOBAL, service.global())

        rows.setRows(out, moreCount = (total - shownCount).coerceAtLeast(0))
    }

    private fun matches(s: CommandSnippet, query: String): Boolean =
        query.isEmpty() ||
            s.description.lowercase().contains(query) ||
            s.command.lowercase().contains(query)

    private fun selectedRef(): SnippetRef? = rows.selectedUserData as? SnippetRef

    private fun defaultScope(): SnippetScope =
        if (sessionId.isNotBlank()) SnippetScope.SESSION else SnippetScope.GLOBAL

    private fun scopeSessionId(scope: SnippetScope): String =
        if (scope == SnippetScope.SESSION) sessionId else ""

    private inner class AddAction : DumbAwareAction("Add Command", "Save a new command", AllIcons.General.Add) {
        override fun actionPerformed(e: AnActionEvent) {
            val edit = SnippetEdit(scope = defaultScope())
            val dialog = CommandSnippetDialog(project, edit, isNew = true, allowSession = sessionId.isNotBlank(), sessionName = sessionName)
            if (dialog.showAndGet()) {
                service.add(edit.command, edit.description, scopeSessionId(edit.scope))
            }
        }
    }

    private inner class EditAction : DumbAwareAction("Edit Command", "Edit the selected command", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            val ref = selectedRef() ?: return
            val edit = SnippetEdit(ref.snippet.description, ref.snippet.command, ref.scope)
            val dialog = CommandSnippetDialog(project, edit, isNew = false, allowSession = sessionId.isNotBlank(), sessionName = sessionName)
            if (dialog.showAndGet()) {
                // Scope changes = remove from old bucket, add to the new one.
                if (edit.scope != ref.scope) {
                    service.remove(ref.snippet.id, scopeSessionId(ref.scope))
                    service.add(edit.command, edit.description, scopeSessionId(edit.scope))
                } else {
                    service.update(ref.snippet.id, edit.command, edit.description, scopeSessionId(ref.scope))
                }
            }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedRef() != null
        }
    }

    private inner class DeleteAction : DumbAwareAction("Delete Command", "Delete the selected command", AllIcons.General.Remove) {
        override fun actionPerformed(e: AnActionEvent) {
            val ref = selectedRef() ?: return
            service.remove(ref.snippet.id, scopeSessionId(ref.scope))
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedRef() != null
        }
    }

    private companion object {
        const val MAX_ROWS = 300
    }
}
