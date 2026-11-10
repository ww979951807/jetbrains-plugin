package com.haifasoft.haifassh.terminal.assist

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.haifasoft.haifassh.settings.SshCommandHistoryService
import java.awt.BorderLayout
import javax.swing.event.DocumentEvent

/**
 * "History" tab: every command typed into this host's shells, most-recent first,
 * with a live search box and a copy button per row. Double-clicking a command
 * types it back into the terminal. Refreshes live as new commands are recorded
 * (see [SshCommandHistoryService.TOPIC]) and persists across reconnects.
 */
class CommandHistoryTab(
    private val hostKey: String,
    typeText: (String) -> Unit,
    parentDisposable: Disposable,
) : JBPanel<CommandHistoryTab>(BorderLayout()) {

    private val history = SshCommandHistoryService.getInstance()
    private val search = SearchTextField()
    private val rows = CommandRowList(typeText)

    init {
        search.border = JBUI.Borders.empty(4)
        add(search, BorderLayout.NORTH)
        add(rows, BorderLayout.CENTER)

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })

        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(SshCommandHistoryService.TOPIC, SshCommandHistoryService.Listener { changed ->
                if (changed == hostKey) ApplicationManager.getApplication().invokeLater { refresh() }
            })

        refresh()
    }

    private fun refresh() {
        val query = search.text.trim().lowercase()
        val all = history.history(hostKey).asReversed() // newest first
        val matches = if (query.isEmpty()) all else all.filter { it.command.lowercase().contains(query) }
        val shown = matches.take(MAX_ROWS)
        rows.setRows(
            shown.map {
                CommandRowList.Row(
                    primary = it.command,
                    secondary = DateFormatUtil.formatPrettyDateTime(it.epochMillis),
                    command = it.command,
                    monospacePrimary = true,
                )
            },
            moreCount = (matches.size - shown.size).coerceAtLeast(0),
        )
    }

    private companion object {
        const val MAX_ROWS = 300
    }
}
