package com.haifasoft.haifassh.terminal

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys

/**
 * Shown in the right-click menu of a terminal tab title (the platform's
 * ToolWindowTabContextMenu group): opens another session to the same server —
 * the same effect as clicking the server again in the session list.
 */
class DuplicateSessionAction : AnAction() {

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = sessionOf(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val session = sessionOf(e) ?: return
    val project = e.project ?: return
    SshTerminalManager.getInstance(project).connect(session)
  }

  // The platform's tab popup data context exposes the owning tool window; the session rides
  // on the selected content as user data (written by SshTerminalManager.openTerminalTab).
  // (Same selected-content basis the platform's own Close menu action uses.)
  private fun sessionOf(e: AnActionEvent) =
    e.getData(PlatformDataKeys.TOOL_WINDOW)?.contentManager?.selectedContent?.getUserData(SshTerminalManager.SESSION_KEY)
}
