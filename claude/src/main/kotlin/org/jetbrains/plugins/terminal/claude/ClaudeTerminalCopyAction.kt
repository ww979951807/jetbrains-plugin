// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.terminal.frontend.view.TerminalView
import java.awt.datatransfer.StringSelection

/**
 * Override of the platform Terminal.CopySelectedText action (TerminalCopyTextAction).
 *
 * In Claude session tabs the terminal keeps the selection in the frontend
 * [TerminalView#getTextSelectionModel], while the stock action's enabled check reads the
 * popup-time editor selection — which is empty there, so the Copy item stays grey.
 * This override copies from the terminal selection model when one exists and otherwise
 * copies from the regular editor selection, so other terminal tabs keep working.
 */
class ClaudeTerminalCopyAction : AnAction() {

  init {
    templatePresentation.text = "Copy"
  }

  private fun terminalSelectionText(e: AnActionEvent): String? {
    val view = e.getData(TerminalView.DATA_KEY) ?: return null
    val selection = view.textSelectionModel.selection ?: return null
    return runCatching {
      view.outputModels.active.value.getText(selection.startOffset, selection.endOffset).toString()
    }.getOrNull()
  }

  private fun editorSelectionText(e: AnActionEvent): String? =
    e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText

  override fun actionPerformed(e: AnActionEvent) {
    val text = terminalSelectionText(e) ?: editorSelectionText(e) ?: return
    if (text.isNotEmpty()) {
      CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = !terminalSelectionText(e).isNullOrEmpty()
        || (e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
