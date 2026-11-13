// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.terminal.frontend.view.TerminalView
import org.jetbrains.plugins.terminal.action.TerminalCopyTextAction
import java.awt.datatransfer.StringSelection

/**
 * Override of the platform [Terminal.CopySelectedText] action (TerminalCopyTextAction).
 *
 * In Claude session tabs the terminal keeps the selection in the frontend
 * [TerminalView#getTextSelectionModel], while the stock action's enabled check reads the
 * popup-time editor selection — which is empty there, so the Copy item stays grey.
 * This override copies from the terminal selection model when one exists and otherwise
 * delegates to the stock action unchanged, so regular terminal tabs keep their behaviour.
 */
class ClaudeTerminalCopyAction : AnAction() {

  private val fallback = TerminalCopyTextAction()

  init {
    templatePresentation.text = "Copy"
  }

  private fun selectionText(e: AnActionEvent): String? {
    val view = e.getData(TerminalView.DATA_KEY) ?: return null
    val selection = view.textSelectionModel.selection ?: return null
    return runCatching {
      view.outputModels.active.value.getText(selection.startOffset, selection.endOffset).toString()
    }.getOrNull()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val text = selectionText(e)
    if (!text.isNullOrEmpty()) {
      CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
    else {
      fallback.actionPerformed(e)
    }
  }

  override fun update(e: AnActionEvent) {
    fallback.update(e)
    if (!selectionText(e).isNullOrEmpty()) {
      e.presentation.isEnabled = true
    }
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
