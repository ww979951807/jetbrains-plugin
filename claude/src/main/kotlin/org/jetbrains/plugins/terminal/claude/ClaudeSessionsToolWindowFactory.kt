// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.claude

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.WideSelectionListUI
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

// The platform's Terminal tool window id (same in 2026.2 and the 2026.3-based rebased IDE).
private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

internal class ClaudeSessionsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun shouldBeAvailable(project: Project): Boolean = true

  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = "Claude Sessions"
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = ClaudeSessionsPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, null, true)
    // Stops the toolwindow-show listener when this content goes away.
    content.setDisposer(panel.listenerDisposable)
    toolWindow.contentManager.addContent(content)
  }
}

internal data class ClaudeSessionInfo(val id: String, val title: String, val lastModified: Long, val sizeBytes: Long, val messageCount: Int)

internal class ClaudeSessionsPanel(private val project: Project) : SimpleToolWindowPanel(true) {
  private val listModel = DefaultListModel<ClaudeSessionInfo>()
  private val list = JBList(listModel)
  // Disposes the message-bus connection (owned by the toolwindow content's disposer).
  val listenerDisposable = Disposer.newDisposable("ClaudeSessionsPanel")
  @Volatile private var newestFirst = true
  private val aiTitleRegex = Regex("\"aiTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
  private val textRegex = Regex("\"type\"\\s*:\\s*\"text\"\\s*,\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
  private val typeRegex = Regex("\"type\"\\s*:\\s*\"(\\w+)\"")

  init {
    toolbar = buildToolbar().component
    setContent(JBScrollPane(list))
    // Platform-native wide-selection UI: paints the selection edge-to-edge across the full
    // row (the prototype's color band) instead of the padded rounded rect.
    list.setUI(WideSelectionListUI())
    list.cellRenderer = SessionRenderer()
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0 || listModel.size() <= index) return
        val bounds = list.getCellBounds(index, index) ?: return
        if (!bounds.contains(e.point)) return
        if (SwingUtilities.isLeftMouseButton(e)) {
          val info = listModel[index]
          if (e.clickCount >= 2) {
            // Double click: open the session (switch to its tab or start it in a new one).
            list.selectedIndex = index
            openSession(info)
          }
          else if (e.clickCount == 1) {
            // Single click: keep the row selection (color overlay) in sync, and switch to
            // the tab only when it is already open — never start anything.
            list.selectedIndex = index
            switchToOpenTab(info)
          }
        }
        else if (SwingUtilities.isRightMouseButton(e)) {
          list.selectedIndex = index
          showContextMenu(e, listModel[index])
        }
      }
    })
    // The Terminal tool window may not exist yet when this panel is created — attaching the
    // content listener here used to silently no-op (the toolWindow getter returned null), so
    // closing a background tab never repainted the open-dots. Attach now if it exists, and
    // again on every toolwindow-show event until it does.
    attachTerminalListener()
    project.messageBus.connect(listenerDisposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
      override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == TERMINAL_TOOL_WINDOW_ID) attachTerminalListener()
      }
    })
    // The platform renames terminal tabs from the process title (OSC) and has no "never" mode
    // for it, so a tab header can grow unbounded (claude CLI sets long session titles).
    // Clamp every terminal tab title to 10 chars while the panel is alive — the platform only
    // renames on title *changes*, so our trim sticks until the next title event.
    val titleClamp = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
      SwingUtilities.invokeLater {
        TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents?.forEach { c ->
          val name = c.displayName
          if (name.length > 10) c.setDisplayName(abbreviate(name, 10))
        }
      }
    }, 2, 2, TimeUnit.SECONDS)
    Disposer.register(listenerDisposable) { titleClamp.cancel(false) }
    refresh()
  }

  // Repaints the open-dots when terminal tabs appear or disappear.
  private var terminalListenerAttached = false

  private fun attachTerminalListener() {
    if (terminalListenerAttached) return
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
    tw.addContentManagerListener(object : ContentManagerListener {
      override fun contentRemoved(event: ContentManagerEvent) = list.repaint()
      override fun contentAdded(event: ContentManagerEvent) = list.repaint()
    })
    terminalListenerAttached = true
  }

  private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
    val group = DefaultActionGroup()
    group.add(object : AnAction("New Session", "Start a new Claude session", AllIcons.General.Add) {
      override fun actionPerformed(e: AnActionEvent) {
        openClaude("claude:new", "claude:new", "claude")
        watchForNewSessionFile()
      }
    })
    group.add(object : AnAction("Refresh", "Rescan Claude sessions for this project", AllIcons.Actions.Refresh) {
      override fun actionPerformed(e: AnActionEvent) = refresh()
    })
    group.add(object : AnAction(
      if (newestFirst) "Newest Last" else "Newest First",
      "Toggle sort order of the session list",
      AllIcons.Ide.UpDown
    ) {
      override fun actionPerformed(e: AnActionEvent) {
        newestFirst = !newestFirst
        refresh()
      }
    })
    return ActionManager.getInstance().createActionToolbar("ClaudeSessionsToolbar", group, true)
  }

  private fun refresh() {
    AppExecutorUtil.getAppExecutorService().execute {
      val sessions = readSessions()
      SwingUtilities.invokeLater {
        listModel.clear()
        sessions.forEach { listModel.addElement(it) }
      }
    }
  }

  private fun readSessions(): List<ClaudeSessionInfo> {
    val dir = projectDir()
    if (!dir.isDirectory) return emptyList()
    val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
    val sessions = files.asSequence()
      .filter { it.length() > 0 }
      .map { f ->
        val id = f.name.removeSuffix(".jsonl")
        ClaudeSessionInfo(id, extractTitle(f) ?: "(no title)", f.lastModified(), f.length(), countMessages(f))
      }
      .sortedByDescending { it.lastModified }
      .toList()
    return if (newestFirst) sessions else sessions.asReversed()
  }

  private fun countMessages(file: File): Int {
    var count = 0
    runCatching {
      file.useLines { lines ->
        for (line in lines) {
          val type = typeRegex.find(line)?.groupValues?.get(1)
          if (type == "user" || type == "assistant") count++
        }
      }
    }
    return count
  }

  private fun extractTitle(file: File): String? {
    var aiTitle: String? = null
    var firstUser: String? = null
    runCatching {
      file.useLines { lines ->
        for (line in lines) {
          // ai-title lines: {"type":"ai-title","aiTitle":"..."} — detect by key presence, the
          // typeRegex (\w+) cannot match the hyphenated "ai-title" value. Take the LAST one.
          if (line.contains("\"aiTitle\"")) {
            val s = aiTitleRegex.find(line)?.groupValues?.get(1)
            if (!s.isNullOrBlank()) {
              aiTitle = unescape(s).trim().replace(Regex("\\s+"), " ").take(60)
            }
          }
          if (firstUser == null) {
            val type = typeRegex.find(line)?.groupValues?.get(1)
            if (type == "user") {
              val raw = textRegex.find(line)?.groupValues?.get(1)
                ?: stringContent(line)
              if (raw != null) {
                val text = unescape(raw).trim().replace(Regex("\\s+"), " ")
                if (text.isNotEmpty() && !text.startsWith("<") && !text.startsWith("[")) {
                  firstUser = text.take(60)
                }
              }
            }
          }
        }
      }
    }
    return aiTitle ?: firstUser
  }

  private fun stringContent(line: String): String? {
    if (!line.contains("\"role\"\\s*:\\s*\"user\"".toRegex())) return null
    val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line) ?: return null
    return m.groupValues.getOrNull(1)
  }

  private fun unescape(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        when (val n = s[i + 1]) {
          'n' -> { sb.append('\n'); i += 2 }
          't' -> { sb.append('\t'); i += 2 }
          'r' -> { sb.append('\r'); i += 2 }
          '"' -> { sb.append('"'); i += 2 }
          '\\' -> { sb.append('\\'); i += 2 }
          '/' -> { sb.append('/'); i += 2 }
          'u' -> {
            val code = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
            if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
          }
          else -> { sb.append(n); i += 2 }
        }
      }
      else {
        sb.append(c)
        i++
      }
    }
    return sb.toString()
  }

  private fun projectDir(): File {
    val cfg = File(System.getProperty("user.home"), ".claude/projects")
    val slug = (project.basePath ?: "").replace(Regex("[^A-Za-z0-9]"), "-")
    return File(cfg, slug)
  }

  private fun sessionFile(id: String) = File(projectDir(), "$id.jsonl")

  // Open-tab lookup goes through the Content userData marker written at creation time;
  // displayName matching is unreliable because the shell process title overrides it.
  private fun tabNameOf(id: String) = "claude:${id.take(8)}"

  private fun findTabContent(id: String): Content? {
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return null
    return tw.contentManager.contents.firstOrNull {
      it.getUserData(TAB_NAME_KEY) == tabNameOf(id) || it.displayName == tabNameOf(id)
    }
  }

  private fun isSessionOpen(id: String): Boolean =
    findTabContent(id) != null

  // displayTitle = the list's title, so the tab shows the session name immediately —
  // otherwise it first flashes "claude:<id>" until the CLI's OSC title arrives and renames it.
  private fun openSession(info: ClaudeSessionInfo) = openClaude(tabNameOf(info.id), info.title, "claude --resume ${info.id}")

  // Single click: switch to the session's terminal tab only when it is already open.
  private fun switchToOpenTab(info: ClaudeSessionInfo) {
    val content = findTabContent(info.id) ?: return
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
    tw.activate { tw.contentManager.setSelectedContent(content, true) }
  }

  private fun openClaude(tabName: String, displayTitle: String, command: String) {
    val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
    val existing = toolWindow?.contentManager?.contents?.firstOrNull { it.getUserData(TAB_NAME_KEY) == tabName || it.displayName == tabName }
    if (existing != null) {
      toolWindow.activate {
        toolWindow.contentManager.setSelectedContent(existing, true)
      }
      return
    }
    startCommand(tabName, displayTitle, command)
  }

  private fun rerunSession(info: ClaudeSessionInfo) {
    val tabName = tabNameOf(info.id)
    val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
    val existing = toolWindow?.contentManager?.contents?.firstOrNull { it.getUserData(TAB_NAME_KEY) == tabName || it.displayName == tabName }
    if (existing != null) {
      runCatching { existing.manager?.removeContent(existing, true) }
    }
    startCommand(tabName, info.title, "claude --resume ${info.id}")
  }

  // Follow the Terminal Engine setting: REWORKED goes through the frontend-module runner
  // (Reworked engine renderer), anything else uses the classic API. Falls back to classic
  // when the frontend service is unavailable.
  private fun startCommand(tabName: String, displayTitle: String, command: String) {
    val workDir = project.basePath
    ApplicationManager.getApplication().invokeLater {
      val before = TerminalToolWindowManager.getInstance(project).toolWindow
        ?.contentManager?.contents?.toSet() ?: emptySet()
      var started = false
      if (TerminalOptionsProvider.instance.terminalEngine == TerminalEngine.REWORKED) {
        started = runCatching { TabCommandRunner(project).createTabAndRunCommand(workDir, tabName, abbreviate(displayTitle, 10), command) }
          .getOrDefault(false)
      }
      if (!started) {
        runCatching {
          val widget: ShellTerminalWidget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workDir, abbreviate(displayTitle, 10)) ?: return@invokeLater
          widget.executeCommand(command)
        }
      }
      // Classic path has no direct Content handle: mark whatever tabs appeared.
      TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents
        ?.filter { it !in before }
        ?.forEach { it.putUserData(TAB_NAME_KEY, tabName) }
      list.repaint()
    }
  }

  @Volatile private var newSessionWatch: ScheduledFuture<*>? = null

  // After starting a new session, poll until the session jsonl appears, refresh the list and
  // rename the "claude:new" tab to "claude:<shortId>" so the open-session dot lights up.
  private fun watchForNewSessionFile() {
    val clickTime = System.currentTimeMillis()
    val counter = AtomicInteger(0)
    newSessionWatch?.cancel(false)
    newSessionWatch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
      SwingUtilities.invokeLater {
        val n = counter.incrementAndGet()
        val tw = TerminalToolWindowManager.getInstance(project).toolWindow
        val content = tw?.contentManager?.contents
          ?.firstOrNull { it.getUserData(TAB_NAME_KEY) == "claude:new" || it.displayName == "claude:new" }
        val fresh = projectDir().listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
          ?.filter { it.lastModified() > clickTime }
          ?.maxByOrNull { it.lastModified() }
        var title: String? = null
        if (fresh != null) {
          val id = fresh.name.removeSuffix(".jsonl")
          content?.putUserData(TAB_NAME_KEY, "claude:${id.take(8)}")
          // Show the real session title as soon as the CLI writes it into the jsonl (same
          // parsing as the list); until then keep the placeholder and keep polling.
          title = extractTitle(fresh)
          if (title != null && content != null) content.setDisplayName(abbreviate(title, 10))
        }
        if (content == null || n >= 40 || (fresh != null && title != null)) {
          newSessionWatch?.cancel(false)
          newSessionWatch = null
        }
        refresh()
      }
    }, 3, 3, TimeUnit.SECONDS)
  }

  private fun showContextMenu(e: MouseEvent, info: ClaudeSessionInfo) {
    val group = DefaultActionGroup()
    group.add(object : AnAction("Rerun", "Reopen this session in a new terminal tab", AllIcons.Actions.Rerun) {
      override fun actionPerformed(e: AnActionEvent) = rerunSession(info)
    })
    group.add(object : AnAction("Rename", "Set a custom title for this session", AllIcons.Actions.Edit) {
      override fun actionPerformed(e: AnActionEvent) {
        // The CLI rewrites the jsonl while its tab is alive and would clobber the rename —
        // close the tab first, with the user's confirmation.
        if (isSessionOpen(info.id)) {
          val answer = Messages.showYesNoDialog(project,
            "Session is open, close and rename?\n${info.title}",
            "Rename Session", Messages.getQuestionIcon())
          if (answer != Messages.YES) return@actionPerformed
          findTabContent(info.id)?.let { content ->
            runCatching { content.manager?.removeContent(content, true) }
          }
        }
        val input = Messages.showInputDialog(project, "Session title:", "Rename Session",
                                             Messages.getQuestionIcon(), info.title, null) ?: return@actionPerformed
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@actionPerformed
        renameSession(info.id, trimmed)
        refresh()
      }
    })
    group.addSeparator()
    group.add(object : AnAction("Delete", "Delete this session", AllIcons.Actions.GC) {
      override fun actionPerformed(e: AnActionEvent) {
        val opened = isSessionOpen(info.id)
        val message = if (opened) "Session is open, close and delete?\n${info.title}"
                      else "Delete this Claude session?\n${info.title}"
        val answer = Messages.showYesNoDialog(project, message,
          "Delete Session", Messages.getQuestionIcon())
        if (answer != Messages.YES) return@actionPerformed
        findTabContent(info.id)?.let { content ->
          runCatching { content.manager?.removeContent(content, true) }
        }
        runCatching { sessionFile(info.id).delete() }
        refresh()
      }
    })
    val dataContext = SimpleDataContext.builder().build()
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
      null, group, dataContext,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
    popup.show(RelativePoint(e))
  }

  // Writes the custom title into the session file itself — the jsonl is the single source of
  // truth shared with the CLI (the old claude-session-titles.txt side-file is gone). The value
  // in the last ai-title record is replaced; when the CLI hasn't titled the session yet a new
  // ai-title record is appended (extractTitle takes the LAST one, so the rename wins). Only
  // runs with the session's tab closed — a live CLI would clobber the edit on its next write.
  private fun renameSession(id: String, title: String) {
    val file = sessionFile(id)
    if (!file.isFile) return
    runCatching {
      val lines = file.readLines().toMutableList()
      val escaped = jsonEscape(title)
      val last = lines.indexOfLast { it.contains("\"type\":\"ai-title\"") || it.contains("\"type\": \"ai-title\"") }
      if (last >= 0) {
        // The lambda form bypasses $-group substitution in the replacement string.
        lines[last] = Regex("\"aiTitle\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")
          .replace(lines[last]) { "\"aiTitle\":\"$escaped\"" }
      } else {
        lines.add("{\"type\":\"ai-title\",\"aiTitle\":\"$escaped\"}")
      }
      val nl = if (file.readText().contains("\r\n")) "\r\n" else "\n"
      val tmp = File(file.parentFile, "${file.name}.rename-tmp")
      tmp.writeText(lines.joinToString(nl) + nl)
      val target = file.toPath()
      try {
        Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  private fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) when (c) {
      '\\' -> sb.append("\\\\")
      '"' -> sb.append("\\\"")
      '\n' -> sb.append("\\n")
      '\r' -> sb.append("\\r")
      '\t' -> sb.append("\\t")
      else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.toString()
  }

  private val sessionIcon = IconLoader.getIcon("/icons/rebased/claudeSessions.svg", ClaudeSessionsPanel::class.java)
  private val dotIcon = IconLoader.getIcon("/icons/rebased/sessionDot.svg", ClaudeSessionsPanel::class.java)

  // Fixed character caps: title line 15 chars, meta line 25 — independent of list width.
  private fun abbreviate(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars) + "…"

  // Native two-line row per the approved prototype: title line = green open-dot (when the
  // session has a terminal tab) + bold title; meta line in gray. The selection band is painted
  // by the renderer itself (see getListCellRendererComponent) — full row, always blue.
  private inner class SessionRenderer : JPanel(BorderLayout()), ListCellRenderer<ClaudeSessionInfo> {
    private val titleLine = SimpleColoredComponent()
    private val metaLine = SimpleColoredComponent()

    init {
      isOpaque = false
      val lines = JPanel(GridLayout(2, 1))
      lines.isOpaque = false
      // SimpleColoredComponent's constructor sets setOpaque(true) — left alone, each line paints
      // its own white background over the selection band, leaving blue only at the row padding
      // (the "ring with white middle"). Both lines must be transparent.
      titleLine.isOpaque = false
      metaLine.isOpaque = false
      lines.add(titleLine)
      lines.add(metaLine)
      titleLine.border = JBUI.Borders.emptyBottom(4)
      titleLine.iconTextGap = 8
      add(lines, BorderLayout.CENTER)
      border = JBUI.Borders.empty(10, 12, 10, 10)
    }

    override fun getListCellRendererComponent(list: JList<out ClaudeSessionInfo>, value: ClaudeSessionInfo?, index: Int, selected: Boolean, focused: Boolean): Component {
      val info = value ?: return this
      titleLine.clear()
      metaLine.clear()
      val open = isSessionOpen(info.id)
      titleLine.icon = if (open) dotIcon else sessionIcon
      titleLine.append(abbreviate(info.title, 25), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
      val time = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(info.lastModified))
      val meta = "$time · ${formatSize(info.sizeBytes)} · ${info.id.take(8)} · ${info.messageCount}"
      metaLine.append(meta, SimpleTextAttributes.GRAYED_ATTRIBUTES)
      // JList asks the renderer component itself (this panel) for the tooltip — the inner
      // lines are never queried, so the full title rides on the panel.
      toolTipText = info.title
      // Colors resolve from the current theme at paint time (not from the list component,
      // whose UI-installed values go stale when the theme switches): title text is the theme's
      // list foreground (black on light / white on dark, same when selected), the band is the
      // theme's selection background, unselected rows the theme's list background.
      foreground = UIUtil.getListForeground(false)
      // The selection band is painted by the renderer itself, edge to edge, so it looks right
      // no matter which list UI ends up active. Always the focused blue: clicking a row whose
      // tab is open moves focus to the terminal, and the band must stay blue regardless.
      isOpaque = selected
      background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground(false)
      return this
    }
  }

  private fun formatSize(bytes: Long): String =
    when {
      bytes >= 1048576L -> String.format("%.1fMB", bytes / 1048576.0)
      bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
      else -> "${bytes}B"
    }
}
