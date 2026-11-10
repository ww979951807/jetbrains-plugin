// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager

/**
 * Bridge to the Reworked Terminal frontend content module: creates a Reworked engine tab
 * and runs a command in it. Falls back to the classic API in the caller when this throws.
 */
class TabCommandRunner(private val project: Project) {
  fun createTabAndRunCommand(workingDirectory: String?, tabName: String, command: String): Boolean {
    return try {
      val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
        .workingDirectory(workingDirectory)
        .tabName(tabName)
        .createTab()
      tab.view.createSendTextBuilder()
        .shouldExecute()
        .send(command)
      true
    }
    catch (t: Throwable) {
      Logger.getInstance(TabCommandRunner::class.java)
        .warn("Failed to create Reworked terminal tab '$tabName'", t)
      false
    }
  }
}
