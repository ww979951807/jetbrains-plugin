// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.scripts

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager

// Marker written onto the tab's Content at creation time. The tab's displayName gets
// overridden by the process title, so displayName matching is unreliable —
// lookups must use this key (Key equality is name-based).
val TAB_NAME_KEY: Key<String> = Key.create("rebased.script.tab.name")

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
      tab.content.putUserData(TAB_NAME_KEY, tabName)
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
