// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView
import org.jetbrains.plugins.terminal.view.TerminalOffset
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import java.awt.datatransfer.StringSelection

/**
 * Override of the platform Terminal.CopySelectedText action (TerminalCopyTextAction).
 *
 * The stock action's enabled check reads the popup-time editor selection; in Claude
 * session tabs that context resolves to the prompt editor (empty selection), so Copy
 * stays grey even with output selected. This override enables on any selection the
 * frontend terminal reports and copies from the terminal output model — with editor
 * selections as fallbacks.
 *
 * Must be DumbAware: the stock action is too, and a non-DumbAware action is force-disabled
 * in every popup while the IDE is indexing (i.e. for the first minutes after each restart).
 */
class ClaudeTerminalCopyAction : DumbAwareAction() {

  init {
    templatePresentation.text = "Copy"
  }

  private val log = Logger.getInstance(ClaudeTerminalCopyAction::class.java)

  // Whether ANY selection exists. Deliberately does not run text extraction here:
  // extraction failures (offset range races) must not grey out an item the user can
  // still invoke; actionPerformed retries with fallbacks.
  private fun hasSelection(e: AnActionEvent): Boolean {
    val view = e.getData(TerminalView.DATA_KEY)
    if (view != null) {
      try {
        if (view.textSelectionModel.selection != null) return true
      }
      catch (t: Throwable) {
        log.debug("terminal selection probe failed", t)
      }
    }
    if (e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true) return true
    // The frontend panel publishes its editor under this key (uiDataSnapshot); the
    // stock action reads it. Check it too so we never grey out on a data-key miss.
    val terminalEditor = TERMINAL_EDITOR_KEY?.let { e.getData(it) }
    if (terminalEditor?.selectionModel?.hasSelection() == true) return true
    return false
  }

  private fun terminalSelectionText(e: AnActionEvent): String? {
    val view = e.getData(TerminalView.DATA_KEY) ?: return null
    val selection = view.textSelectionModel.selection ?: return null
    val model: TerminalOutputModel = view.outputModels.active.value
    val start = selection.startOffset
    val end = selection.endOffset
    return runCatching {
      model.getText(start, end).toString()
    }.recoverCatching {
      // Offset races: clamp to the model's current range (as absolute longs —
      // TerminalOffset has no coerce helpers) and retry once.
      val lo = minOf(start.toAbsolute(), end.toAbsolute())
      val hi = maxOf(start.toAbsolute(), end.toAbsolute())
      val s = maxOf(lo, model.startOffset.toAbsolute())
      val t = minOf(hi, model.endOffset.toAbsolute())
      if (t <= s) return@recoverCatching ""
      model.getText(TerminalOffset.of(s), TerminalOffset.of(t)).toString()
    }.getOrNull()
  }

  private fun editorSelectionText(e: AnActionEvent): String? =
    e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        ?: TERMINAL_EDITOR_KEY?.let { e.getData(it) }?.selectionModel?.selectedText

  override fun actionPerformed(e: AnActionEvent) {
    val text = terminalSelectionText(e) ?: editorSelectionText(e) ?: return
    if (text.isNotEmpty()) {
      CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = hasSelection(e)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  companion object {
    // org.jetbrains.plugins.terminal.action util publishes DataKey<Editor> under this
    // id; resolved reflectively to avoid a compile-time dependency on the terminal
    // plugin's internal classes.
    private val TERMINAL_EDITOR_KEY by lazy {
      runCatching {
        val clazz = Class.forName("com.intellij.terminal.actions.TerminalActionUtil")
        val field = clazz.getField("EDITOR_KEY")
        @Suppress("UNCHECKED_CAST")
        field.get(null) as com.intellij.openapi.actionSystem.DataKey<com.intellij.openapi.editor.Editor>
      }.getOrNull()
    }
  }
}
