# Understanding the IntelliJ IDEA Plugin System

A from-scratch guide to how JetBrains plugins work, written so you can read the
rest of this project's docs (and its code) and understand *why* it is shaped the
way it is. Everything here is general to the IntelliJ Platform; the next document
([02-implementation.md](02-implementation.md)) maps it onto *this* plugin.

---

## 1. What "the IntelliJ Platform" actually is

IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, Android Studio, etc. are all the
**same core application** — the *IntelliJ Platform* — with different bundled
plugins on top. When you write a plugin you are not writing "an IDEA add-on"; you
are adding code to that shared platform. That is why one plugin built against the
platform can run in *any* of those IDEs (subject to the APIs it uses being
present).

The platform gives you:

- A **component/service container** (dependency-injection-like) that owns object
  lifecycles.
- A **UI toolkit** built on Swing, with JetBrains' own components (`JBList`,
  `JBTextField`, `DialogWrapper`, `ToolWindow`, …) and theming (the "New UI",
  dark/light themes, HiDPI scaling).
- A **virtual file system (VFS)**, an editor, a project model, indexing, a
  background-task framework, notifications, a credential store, and much more.
- An **extension mechanism** so your code can plug into all of the above.

Your plugin is distributed as a **ZIP** containing one or more JARs plus a
descriptor (`plugin.xml`). The user installs it via *Settings → Plugins → Install
Plugin from Disk*, or from the JetBrains Marketplace.

---

## 2. The two pillars: `plugin.xml` and the platform APIs

Every plugin has exactly one core descriptor: **`META-INF/plugin.xml`**. It is the
contract between your code and the platform. It declares:

| Element | Purpose |
|--------|---------|
| `<id>` | Globally-unique plugin id (reverse-DNS, e.g. `com.haifasoft.haifassh`). |
| `<name>` / `<version>` / `<vendor>` | Marketplace + UI metadata. |
| `<description>` / `<change-notes>` | HTML shown in the Plugins UI. |
| `<depends>` | Other plugins/modules this one needs (e.g. `com.intellij.modules.platform`). |
| `<idea-version since-build="…">` | Compatibility range (build numbers, not versions). |
| `<extensions>` | **Where you hook into the platform** (see §4). |
| `<actions>` | Menu items / toolbar buttons / keyboard-shortcut-bound commands. |
| `<applicationListeners>` | Declarative message-bus subscribers. |

The platform reads `plugin.xml` at startup, instantiates what it declares, and
wires it into the running IDE.

### Build numbers vs. versions

Compatibility is expressed in **build numbers**, not marketing versions. IDEA
2024.2 is build **242**; 2024.3 is 243; 2025.1 is 251; and so on (the first three
digits are `<year-2000><release>`). `since-build="242"` means "2024.2 and newer".
Omitting `until-build` means "no upper bound" — useful for internal tools that
must survive rolling IDE upgrades, risky for Marketplace plugins that might break
on a future API change.

---

## 3. Services, the component container, and scopes

The platform is built around **services** — singletons it creates lazily and owns.
You never `new` a service; you ask the container for it. A class becomes a service
by being annotated `@Service` (or declared in `plugin.xml`).

Services have a **scope (level)** that determines how many instances exist and how
long they live:

- **Application-level** (`@Service(Service.Level.APP)`) — one instance for the
  whole IDE. Good for global settings, caches, registries.
- **Project-level** (`@Service(Service.Level.PROJECT)`) — one instance per open
  project. Good for anything tied to a project's lifecycle.

You retrieve them with:

```kotlin
val appSvc  = service<MyAppService>()                 // application
val projSvc = project.service<MyProjectService>()     // project
```

Because the container owns lifecycles, services are also natural **`Disposable`
parents**: when a project closes, its project-level services (and anything
registered as their child `Disposable`) are torn down. This is the platform's
answer to "who cleans up my threads/listeners/connections?".

### Persistent state — how plugins remember things

A service can persist data across restarts by implementing
**`PersistentStateComponent<T>`** and annotating itself with `@State(...)`:

```kotlin
@Service(Service.Level.APP)
@State(name = "MySettings", storages = [Storage("my-settings.xml")])
class MySettings : PersistentStateComponent<MyState> {
    private var state = MyState()
    override fun getState() = state
    override fun loadState(s: MyState) { state = s }
}
```

The platform serializes the state object to/from XML automatically using its
**XML serializer** (`com.intellij.util.xmlb`). Rules that matter in practice:

- The state class must be a **plain mutable bean with default values** (so the
  serializer can instantiate it and round-trip every field). This is why model
  classes in platform plugins look like `var name: String = ""` rather than
  idiomatic immutable Kotlin `data class`es.
- Collections often need `@XCollection` to control their XML shape.
- Files land under `<config>/options/` (app-level) or the project's `.idea/`
  (project-level), depending on the `Storage`.
- **Never put secrets here** — see §7.

---

## 4. Extension points — the heart of "plugging in"

The platform exposes hundreds of **extension points (EPs)**: named slots where a
plugin can contribute an implementation of a known interface. You declare a
contribution in `plugin.xml` under `<extensions defaultExtensionNs="com.intellij">`.

A few you will meet constantly (and that this project uses):

| Extension point | You provide | Effect |
|-----------------|-------------|--------|
| `toolWindow` | a `ToolWindowFactory` | A docked panel (left/right/bottom) like *Project* or *Terminal*. |
| `applicationConfigurable` / `projectConfigurable` | a `Configurable` | A page under *Settings/Preferences*. |
| `fileEditorProvider` | a `FileEditorProvider` | A custom editor for certain files (opens as an editor tab). |
| `notificationGroup` | (declaration only) | A named channel for balloon/log notifications. |
| `postStartupActivity` | a `StartupActivity` | Runs work when a project finishes opening. |

The pattern is always the same: **implement the platform's interface, then point
`plugin.xml` at your class.** The platform instantiates it when needed.

### Actions — commands the user can invoke

An **`AnAction`** is a unit of "something the user can do": a toolbar button, a
menu item, or a keyboard-shortcut-bound command. Key pieces:

- `actionPerformed(e)` — what it does.
- `update(e)` — enable/disable/relabel it based on context (selection, etc.).
- `getActionUpdateThread()` — `EDT` (needs Swing state) or `BGT` (background-safe).
  The platform enforces this; getting it wrong throws.
- **`DumbAwareAction`** — an action that is allowed to run while the IDE is
  "dumb" (indexing). Use it when your action doesn't need indexes.

Actions can be registered declaratively (`<actions>` in `plugin.xml`) or
programmatically (`action.registerCustomShortcutSet(...)`, or by building a
`DefaultActionGroup` and handing it to a toolbar/popup). They can be grouped
(`ActionGroup`) and shown as toolbars (`ToolbarDecorator`, `ActionToolbar`) or
context menus (`PopupHandler.installPopupMenu`).

---

## 5. Threading: the EDT, background tasks, and read/write actions

This is the rule that breaks the most newcomer plugins.

- **All Swing/UI access must happen on the Event Dispatch Thread (EDT).** Touch a
  component off-EDT and you get exceptions or corruption.
- **Never block the EDT.** Anything slow — network, SSH, file IO, long
  computation — must run on a background thread, or the whole IDE freezes.

The platform's tools for this:

- `ApplicationManager.getApplication().invokeLater { … }` — run on the EDT later.
- `Task.Backgroundable` — run work on a background thread with a progress bar,
  with `onSuccess`/`onThrowable` callbacks that come back on the EDT. This is the
  canonical "do slow thing, then update UI" pattern.
- **Read/Write actions** — the platform's data model (PSI, VFS, etc.) is guarded
  by a read/write lock. Reads need a *read action*; mutations need a *write
  action* on the EDT. (This plugin barely touches that model, so it mostly only
  deals with the EDT-vs-background split.)

A typical safe flow: gather UI input on the EDT → hop to `Task.Backgroundable`
for the slow part → return to the EDT in `onSuccess` to show the result.

---

## 6. The message bus — decoupled events

The platform has a **publish/subscribe message bus** for loosely-coupled
communication. You define a `Topic`, publishers call
`messageBus.syncPublisher(TOPIC).someEvent()`, and subscribers connect with a
`Disposable` so they auto-unsubscribe:

```kotlin
val topic = Topic.create("my event", MyListener::class.java)
// subscribe (tied to a Disposable so it cleans up):
app.messageBus.connect(parentDisposable).subscribe(topic, MyListener { … })
// publish:
app.messageBus.syncPublisher(topic).changed()
```

The platform itself publishes many useful topics — e.g. `LafManagerListener.TOPIC`
fires when the user switches the IDE's look-and-feel (dark ⇄ light). Plugins
subscribe to react (repaint, re-theme, reload).

This is how a plugin keeps multiple windows/components in sync without them
referencing each other directly.

---

## 7. Credentials — the PasswordSafe

The platform ships a secrets store, **`PasswordSafe`**, backed by the OS keychain
(macOS Keychain, Windows Credential Manager / DPAPI) or an encrypted KeePass file.
Plugins should store passwords/tokens there, keyed by `CredentialAttributes`, and
**never** in their persistent XML state.

```kotlin
val attrs = CredentialAttributes(generateServiceName("MyPlugin", key))
PasswordSafe.instance.setPassword(attrs, secret)   // store
PasswordSafe.instance.getPassword(attrs)           // retrieve
```

Caveat worth knowing (see the security report): the *strength* of PasswordSafe
depends on the user's chosen backend. The OS keychain is strong; a KeePass file
"with no master password" is effectively obfuscation, not encryption.

---

## 8. UI building blocks

Plugin UI is Swing, but you use JetBrains' wrappers so it themes and scales
correctly:

- **Components**: `JBTextField`, `JBPasswordField`, `JBList`, `JBCheckBox`,
  `JBLabel`, `ComboBox`, `JBIntSpinner`, `Tree` (the platform's `JTree`), …
- **Layout**: `FormBuilder` (label/field rows), or the Kotlin UI DSL (`panel { row { … } }`).
- **Dialogs**: extend **`DialogWrapper`** — it gives you OK/Cancel, validation
  (`doValidate`), and correct modality. `createCenterPanel()` returns your form.
- **Tool window content**: a `ToolWindowFactory` builds a Swing component and adds
  it as `Content` to the tool window's `ContentManager`.
- **Trees**: render cells with `ColoredTreeCellRenderer`; add type-to-find with
  `TreeSpeedSearch`; build toolbars around them with `ToolbarDecorator`.
- **Colors/scaling**: use `JBColor` (theme-aware), `JBUI.scale(...)` (HiDPI), and
  `IconLoader.getIcon(...)` for SVG icons that the platform recolors per theme.

### Icons and the New UI

Icons are **SVG**, loaded via `IconLoader`. In the **New UI**, tool-window
*stripe* icons are expected to be **monochrome**; the platform recolors them to
match selected/unselected state. An icon authored in an off-palette color (or as
a multi-color image) may render dimmed/gray instead of adapting — a real gotcha
this project hit (see [02-implementation.md](02-implementation.md) §"Tool window
icons"). The standard monochrome icon color is `#6C707E`.

---

## 9. Building a plugin: the Gradle toolchain

Modern plugins are built with the **IntelliJ Platform Gradle Plugin 2.x**
(`org.jetbrains.intellij.platform`). Conceptually the build:

1. Downloads the target IDE/platform as a dependency (e.g.
   `intellijIdeaCommunity("2024.2")`), so you compile against real platform APIs.
2. Compiles your Kotlin/Java against that platform.
3. Bundles your code + third-party libraries + `plugin.xml` into the plugin ZIP.

Key tasks:

| Task | What it does |
|------|--------------|
| `compileKotlin` | Compiles sources against the platform. |
| `buildPlugin` | Produces the installable ZIP in `build/distributions/`. |
| `runIde` | Launches a sandbox IDE with the plugin installed — your main test loop. |
| `buildSearchableOptions` | Pre-indexes settings pages for search (runs a headless IDE; often noisy). |
| `verifyPlugin` | Checks API compatibility against target IDEs. |

**A critical, non-obvious constraint:** the Kotlin **metadata version** of any
Kotlin-compiled dependency you bundle must be compatible with the platform's
Kotlin compiler. The 2024.2 platform pins Kotlin 1.9; bundling a library compiled
with Kotlin 2.x fails with *"Module was compiled with an incompatible version of
Kotlin"*. This is exactly why this project pins specific dependency versions (see
implementation doc).

---

## 10. Lifecycle & disposal — the mental model

Tie everything that needs cleanup to a **`Disposable`**:

- A tool window gives you `toolWindow.disposable`.
- A `FileEditor` *is* a `Disposable` (its `dispose()` is called when the tab
  closes).
- You can make your own with `Disposer.newDisposable(...)` and register children;
  disposing the parent disposes the children.

When you subscribe to the message bus, open a socket, start a thread, or register
a listener, **scope it to a Disposable** so the platform cleans it up at the right
time. Leaks here cause "ghost" listeners firing after a window closed, or
connections that never close.

---

## 11. Putting it together — the shape of a typical feature

To add, say, "a panel that lists things and lets you act on them," you:

1. Declare a `toolWindow` extension → its `ToolWindowFactory` builds a Swing panel.
2. Hold the data in a `@Service` + `PersistentStateComponent` so it survives
   restarts; keep secrets in `PasswordSafe`.
3. Render the list/tree with platform components and a `ColoredTreeCellRenderer`.
4. Add `AnAction`s (toolbar + context menu + shortcuts) for the operations.
5. Do any slow work in `Task.Backgroundable`, returning to the EDT to update UI.
6. Broadcast changes on a `Topic` so other open windows refresh.
7. Tie listeners/connections/resources to the relevant `Disposable`.

That is precisely the anatomy of this project — read on in
[02-implementation.md](02-implementation.md).
