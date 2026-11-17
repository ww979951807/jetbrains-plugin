// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView

// Marker written onto the tab's Content at creation time. The tab's displayName gets
// overridden by the shell process title, so displayName matching is unreliable —
// lookups must use this key (Key equality is name-based).
val TAB_NAME_KEY: Key<String> = Key.create("rebased.claude.tab.name")

// The tab's stable display title (the session name). The title-restore guard writes this
// back whenever the platform renames the tab from the process's OSC title.
val TAB_TITLE_KEY: Key<String> = Key.create("rebased.claude.tab.title")

// The tab's TerminalView (Reworked engine) — used to check whether the session process
// has really exited before deleting the session file.
val TAB_VIEW_KEY: Key<TerminalView> = Key.create("rebased.claude.tab.view")

/**
 * Bridge to the Reworked Terminal frontend content module: creates a Reworked engine tab
 * and runs a command in it. Falls back to the classic API in the caller when this throws.
 */
class TabCommandRunner(private val project: Project) {
  fun createTabAndRunCommand(
    workingDirectory: String?,
    tabName: String,
    displayTitle: String,
    command: String,
  ): Boolean {
    return try {
      val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
        .workingDirectory(workingDirectory)
        .tabName(displayTitle)
        .createTab()
      tab.content.putUserData(TAB_NAME_KEY, tabName)
      tab.content.putUserData(TAB_TITLE_KEY, displayTitle)
      tab.content.putUserData(TAB_VIEW_KEY, tab.view)
      // Pin the tab title in the platform's own title model. Reworked-engine tab names are
      // rendered from TerminalTitle (updateTabNameOnTitleChange → Content.displayName), and
      // every OSC title update from the CLI rewrites applicationTitle — with userDefinedTitle
      // unset the tab name follows it and flaps between the session name and the CLI title.
      // userDefinedTitle wins over applicationTitle in TerminalTitle.buildTitle, so setting it
      // pins the name; trackTerminalApplicationTitleChanges=false additionally stops the
      // classic bindApplicationTitle path from overwriting the title.
      runCatching {
        tab.view.title.change {
          userDefinedTitle = displayTitle
          trackTerminalApplicationTitleChanges = false
        }
      }
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
