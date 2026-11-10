// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.scripts

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.WideSelectionListUI
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

// The platform's Terminal tool window id (same in 2026.2 and the 2026.3-based rebased IDE).
private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

internal class ScriptsToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun shouldBeAvailable(project: Project): Boolean = true

  override fun init(toolWindow: ToolWindow) {
    toolWindow.stripeTitle = "Scripts"
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = ScriptsPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, null, true)
    // Stops the toolwindow-show listener when this content goes away.
    content.setDisposer(panel.listenerDisposable)
    toolWindow.contentManager.addContent(content)
  }
}

internal class ScriptConfig {
  var name: String = ""
  var isText: Boolean = false
  var script: String = ""
  var workDir: String = ""
}

@Service(Service.Level.PROJECT)
@State(name = "ScriptsToolWindowConfigurations", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ScriptsConfigService : PersistentStateComponent<ScriptsConfigService.State> {
  class State {
    var configs: MutableList<ScriptConfig> = mutableListOf()
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(s: State) {
    state = s
  }

  fun configs(): MutableList<ScriptConfig> = state.configs

  companion object {
    fun getInstance(project: Project): ScriptsConfigService = project.getService(ScriptsConfigService::class.java)
  }
}

internal class ScriptsPanel(private val project: Project) : SimpleToolWindowPanel(true) {
  private val listModel = DefaultListModel<ScriptConfig>()
  private val list = JBList(listModel)
  // Disposes the message-bus connection (owned by the toolwindow content's disposer).
  val listenerDisposable = Disposer.newDisposable("ScriptsPanel")

  init {
    toolbar = buildToolbar().component
    setContent(JBScrollPane(list))
    // Platform-native wide-selection UI: paints the selection edge-to-edge across the full
    // row (the prototype's color band) instead of the padded rounded rect.
    list.setUI(WideSelectionListUI())
    list.cellRenderer = ScriptRenderer()
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0 || listModel.size() <= index) return
        val bounds = list.getCellBounds(index, index) ?: return
        if (!bounds.contains(e.point)) return
        if (SwingUtilities.isLeftMouseButton(e)) {
          val cfg = listModel[index]
          if (e.clickCount >= 2) {
            // Double click: run the script (switch to its tab or start it in a new one).
            list.selectedIndex = index
            runOrSwitch(cfg)
          }
          else if (e.clickCount == 1) {
            // Single click: keep the row selection (color overlay) in sync, and switch to
            // the tab only when it is already open — never run anything.
            list.selectedIndex = index
            switchToOpenTab(cfg)
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
    // closing a background tab never repainted the running-dots. Attach now if it exists, and
    // again on every toolwindow-show event until it does.
    attachTerminalListener()
    project.messageBus.connect(listenerDisposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
      override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == TERMINAL_TOOL_WINDOW_ID) attachTerminalListener()
      }
    })
    // The platform renames terminal tabs from the process title (OSC) and has no "never" mode
    // for it, so a tab header can grow unbounded (a long script name takes half the tab strip).
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
    reloadList()
  }

  // Repaints the running-dots when terminal tabs appear or disappear.
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
    group.add(object : AnAction("Add Script", "Add a new script configuration", AllIcons.General.Add) {
      override fun actionPerformed(e: AnActionEvent) {
        val dialog = ScriptConfigDialog(project, null)
        if (dialog.showAndGet()) {
          ScriptsConfigService.getInstance(project).configs().add(dialog.getCfg())
          reloadList()
        }
      }
    })
    return ActionManager.getInstance().createActionToolbar("ScriptsToolbar", group, true)
  }

  private fun reloadList() {
    listModel.clear()
    ScriptsConfigService.getInstance(project).configs().forEach { listModel.addElement(it) }
  }

  // Tab title = the script name exactly as the list shows it (no prefix).
  private fun tabNameOf(cfg: ScriptConfig) = cfg.name

  // Open-tab lookup goes through the Content userData marker written at creation time;
  // displayName matching is unreliable because the process title overrides it.
  private fun findTabContent(cfg: ScriptConfig): Content? =
    TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents
      ?.firstOrNull { it.getUserData(TAB_NAME_KEY) == tabNameOf(cfg) || it.displayName == tabNameOf(cfg) }

  private fun isOpen(cfg: ScriptConfig): Boolean = findTabContent(cfg) != null

  private fun workDirOf(cfg: ScriptConfig): String? =
    cfg.workDir.trim().ifEmpty { project.basePath }

  private fun commandFor(cfg: ScriptConfig): String {
    if (cfg.isText) return cfg.script
    val ext = File(cfg.script).extension.lowercase(Locale.ROOT)
    val quoted = "\"${cfg.script}\""
    return when (ext) {
      "ps1" -> "powershell -NoProfile -ExecutionPolicy Bypass -File $quoted"
      "bat", "cmd" -> "cmd /c $quoted"
      "py" -> "python $quoted"
      "sh" -> "bash $quoted"
      else -> cfg.script
    }
  }

  // Follow the Terminal Engine setting: REWORKED goes through the frontend-module runner
  // (Reworked engine renderer), anything else uses the classic API. Falls back to classic
  // when the frontend service is unavailable.
  private fun executeInNewTab(tabName: String, workDir: String?, command: String) {
    ApplicationManager.getApplication().invokeLater {
      val before = TerminalToolWindowManager.getInstance(project).toolWindow
        ?.contentManager?.contents?.toSet() ?: emptySet()
      var started = false
      if (TerminalOptionsProvider.instance.terminalEngine == TerminalEngine.REWORKED) {
        started = runCatching { TabCommandRunner(project).createTabAndRunCommand(workDir, tabName, abbreviate(tabName, 10), command) }
          .getOrDefault(false)
      }
      if (!started) {
        runCatching {
          val widget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workDir, abbreviate(tabName, 10)) ?: return@invokeLater
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

  private fun runOrSwitch(cfg: ScriptConfig) {
    val existing = findTabContent(cfg)
    if (existing != null) {
      val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
      tw.activate {
        tw.contentManager.setSelectedContent(existing, true)
      }
      return
    }
    executeInNewTab(tabNameOf(cfg), workDirOf(cfg), commandFor(cfg))
  }

  // Single click: switch to the script's terminal tab only when it is already open.
  private fun switchToOpenTab(cfg: ScriptConfig) {
    val content = findTabContent(cfg) ?: return
    val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
    tw.activate { tw.contentManager.setSelectedContent(content, true) }
  }

  private fun rerunScript(cfg: ScriptConfig) {
    val existing = findTabContent(cfg)
    if (existing != null) {
      runCatching { existing.manager?.removeContent(existing, true) }
    }
    executeInNewTab(tabNameOf(cfg), workDirOf(cfg), commandFor(cfg))
  }

  private fun showContextMenu(e: MouseEvent, cfg: ScriptConfig) {
    val group = DefaultActionGroup()
    group.add(object : AnAction("Rerun", "Run this script in a new terminal tab", AllIcons.Actions.Rerun) {
      override fun actionPerformed(e: AnActionEvent) = rerunScript(cfg)
    })
    group.add(object : AnAction("Edit", "Edit this script configuration", AllIcons.Actions.Edit) {
      override fun actionPerformed(e: AnActionEvent) {
        val dialog = ScriptConfigDialog(project, cfg)
        if (dialog.showAndGet()) {
          val newCfg = dialog.getCfg()
          val configs = ScriptsConfigService.getInstance(project).configs()
          val idx = configs.indexOf(cfg)
          if (idx >= 0) configs[idx] = newCfg else configs.add(newCfg)
          reloadList()
        }
      }
    })
    group.addSeparator()
    group.add(object : AnAction("Delete", "Delete this script configuration", AllIcons.Actions.GC) {
      override fun actionPerformed(e: AnActionEvent) {
        val answer = Messages.showYesNoDialog(project,
          "Delete script configuration \"${cfg.name}\"?",
          "Delete Script", Messages.getQuestionIcon())
        if (answer != Messages.YES) return@actionPerformed
        findTabContent(cfg)?.let { content ->
          runCatching { content.manager?.removeContent(content, true) }
        }
        ScriptsConfigService.getInstance(project).configs().remove(cfg)
        reloadList()
      }
    })
    val dataContext = object : DataContext {
      override fun getData(dataId: String): Any? = null
    }
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
      null, group, dataContext,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
    popup.show(RelativePoint(e))
  }

  private val dotIcon = IconLoader.getIcon("/icons/rebased/sessionDot.svg", ScriptsPanel::class.java)
  private val cmdIcon = IconLoader.getIcon("/icons/rebased/script-cmd.svg", ScriptsPanel::class.java)
  private val ps1Icon = IconLoader.getIcon("/icons/rebased/script-ps1.svg", ScriptsPanel::class.java)
  private val batIcon = IconLoader.getIcon("/icons/rebased/script-bat.svg", ScriptsPanel::class.java)
  private val pyIcon = IconLoader.getIcon("/icons/rebased/script-py.svg", ScriptsPanel::class.java)
  private val shIcon = IconLoader.getIcon("/icons/rebased/script-sh.svg", ScriptsPanel::class.java)
  private val fileIcon = IconLoader.getIcon("/icons/rebased/script-file.svg", ScriptsPanel::class.java)

  private fun iconFor(cfg: ScriptConfig) = when {
    cfg.isText -> cmdIcon
    else -> when (File(cfg.script).extension.lowercase(Locale.ROOT)) {
      "ps1" -> ps1Icon
      "bat", "cmd" -> batIcon
      "py" -> pyIcon
      "sh" -> shIcon
      else -> fileIcon
    }
  }

  // Fixed character caps, independent of list width: title line 15 chars, meta line 25.
  private fun abbreviate(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars) + "…"

  // Native two-line row per the approved prototype: title line = green running-dot (when the
  // script has a terminal tab) + bold name, gray command preview below. WideSelectionListUI
  // (set on the list in init) paints the selection/hover fill edge-to-edge across the FULL
  // row, UNDER the renderer — so the renderer must stay non-opaque or it covers that fill,
  // leaving only the focus ring visible.
  private inner class ScriptRenderer : JPanel(BorderLayout()), ListCellRenderer<ScriptConfig> {
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

    override fun getListCellRendererComponent(list: JList<out ScriptConfig>, value: ScriptConfig?, index: Int, selected: Boolean, focused: Boolean): Component {
      val cfg = value ?: return this
      titleLine.clear()
      metaLine.clear()
      titleLine.icon = if (isOpen(cfg)) dotIcon else iconFor(cfg)
      titleLine.append(abbreviate(cfg.name, 25), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
      metaLine.append(commandFor(cfg), SimpleTextAttributes.GRAYED_ATTRIBUTES)
      // JList asks the renderer component itself (this panel) for the tooltip — the inner
      // lines are never queried, so the full title rides on the panel.
      toolTipText = cfg.name
      foreground = list.foreground
      // The selection band is painted by the renderer itself, edge to edge, so it looks right
      // no matter which list UI ends up active. Always the focused blue: clicking a row whose
      // tab is open moves focus to the terminal, and the band must stay blue regardless.
      isOpaque = selected
      background = if (selected) UIUtil.getListSelectionBackground(true) else list.background
      return this
    }
  }
}

internal class ScriptConfigDialog(private val project: Project, private val existing: ScriptConfig?) : DialogWrapper(project) {
  // Field column width matches the prototype's 520px dialog proportions; height stays native.
  private val nameField = object : JBTextField() {
    override fun getPreferredSize() = java.awt.Dimension(JBUI.scale(380), super.getPreferredSize().height)
  }
  private val fileRadio = JBRadioButton("Script file", true)
  private val textRadio = JBRadioButton("Script text")
  private val fileField = object : TextFieldWithBrowseButton() {
    override fun getPreferredSize() = java.awt.Dimension(JBUI.scale(380), super.getPreferredSize().height)
  }
  private val textArea = JBTextArea(4, 46)
  private val workDirField = object : TextFieldWithBrowseButton() {
    override fun getPreferredSize() = java.awt.Dimension(JBUI.scale(380), super.getPreferredSize().height)
  }
  // The third row's label follows the type toggle ("Script file:" / "Script text:"), like the
  // prototype's two-column form.
  private val contentLabel = javax.swing.JLabel("Script file:")
  private val cards = JPanel(BorderLayout())

  init {
    title = if (existing == null) "Add Script Configuration" else "Edit Script Configuration"
    ButtonGroup().apply { add(fileRadio); add(textRadio) }
    fileField.addBrowseFolderListener("Select Script File", "Select the script file to execute", project,
                                      FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
    workDirField.addBrowseFolderListener("Select Working Directory", "Directory where the command is executed", project,
                                         FileChooserDescriptorFactory.createSingleFolderDescriptor())
    fileRadio.addActionListener { syncTypeUi() }
    textRadio.addActionListener { syncTypeUi() }

    // Only the current field stays in the layout, so the dialog is as tall as that field
    // requires (a CardLayout would stay as tall as the 4-row text area even in file mode).
    cards.add(fileField, BorderLayout.CENTER)

    existing?.let {
      nameField.text = it.name
      fileRadio.isSelected = !it.isText
      textRadio.isSelected = it.isText
      if (it.isText) textArea.text = it.script else fileField.text = it.script
      workDirField.text = it.workDir
    }
    syncTypeUi()
    init()
  }

  private fun syncTypeUi() {
    contentLabel.text = if (fileRadio.isSelected) "Script file:" else "Script text:"
    cards.removeAll()
    cards.add(if (fileRadio.isSelected) fileField else JBScrollPane(textArea), BorderLayout.CENTER)
    cards.revalidate()
    cards.repaint()
    if (isVisible) {
      SwingUtilities.getWindowAncestor(cards)?.pack()
    }
  }

  override fun createCenterPanel(): JComponent {
    val typePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      isOpaque = false
      add(fileRadio)
      add(textRadio)
    }
    return FormBuilder.createFormBuilder()
      .addLabeledComponent("Name:", nameField)
      .addLabeledComponent("Type:", typePanel)
      .addLabeledComponent(contentLabel, cards)
      .addLabeledComponent("Working directory:", workDirField)
      .panel
  }

  override fun doValidate(): ValidationInfo? {
    if (nameField.text.trim().isEmpty()) return ValidationInfo("Name is required", nameField)
    val service = ScriptsConfigService.getInstance(project)
    val duplicate = service.configs().any { it.name == nameField.text.trim() && it !== existing }
    if (duplicate) return ValidationInfo("A script with this name already exists", nameField)
    if (fileRadio.isSelected && fileField.text.trim().isEmpty()) return ValidationInfo("Script file is required", fileField)
    if (textRadio.isSelected && textArea.text.trim().isEmpty()) return ValidationInfo("Script text is required", textArea)
    return null
  }

  fun getCfg(): ScriptConfig = ScriptConfig().apply {
    name = nameField.text.trim()
    isText = textRadio.isSelected
    script = if (isText) textArea.text.trim() else fileField.text.trim()
    workDir = workDirField.text.trim()
  }
}
