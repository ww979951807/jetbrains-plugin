# How HaifaSSH Is Implemented

This maps the generic platform concepts from
[01-intellij-plugin-system.md](01-intellij-plugin-system.md) onto *this* plugin:
what it does, the technology stack, the architecture, and the non-obvious
decisions. For a file-by-file walkthrough, see
[03-code-guide.md](03-code-guide.md).

---

## 1. What the plugin does

HaifaSSH is a **MobaXterm-style SSH session manager embedded in JetBrains IDEs**:

- A **session tree** (folders + sessions) in the left *SSH Sessions* tool window.
- **One-click connect** to an interactive shell, rendered by an embedded terminal
  emulator either as an **editor tab** or in the bottom *SSH Terminals* tool window.
- **Password or private-key auth**, plus **jump hosts** (ProxyJump/bastion chains).
- **Secrets in the IDE PasswordSafe**, never in config files.
- **Import/export** of the whole tree (optionally including secrets).
- **Customizable terminal color schemes** (templates, "Follow IDE Theme", or a
  fully editable custom scheme).
- **Per-session icons & colors**, **per-folder colors**, **in-place rename
  (Shift+F6)**, and **type-to-search** in the tree (added in 0.5.0).

---

## 2. Technology stack

| Layer | Technology | Notes |
|------|-----------|-------|
| Language | **Kotlin 1.9.25** | Matches the 2024.2 platform's Kotlin toolchain. |
| Build | **Gradle 8.10** + **IntelliJ Platform Gradle Plugin 2.1.0** | Wrapper-pinned; system Gradle won't do. |
| Target platform | **IntelliJ IDEA Community 2024.2** (build 242), `sinceBuild=242`, no upper bound | One artifact runs on 2024.2+. |
| JVM | **JDK 17** toolchain | `jvmToolchain(17)`. |
| SSH client | **`com.github.mwiede:jsch:0.2.25`** | Maintained fork of the abandoned JCraft JSch; same `com.jcraft.jsch` package. |
| Terminal emulator | **JediTerm `jediterm-core` / `jediterm-ui:3.53`** | From the JetBrains deps repo, *not* Maven Central. |
| Secrets | Platform **PasswordSafe** | OS keychain / KeePass-backed. |
| UI | Platform Swing (`DialogWrapper`, `Tree`, `ToolbarDecorator`, `ColorPanel`, …) | — |

### Two dependency constraints that drive the versions

1. **JediTerm must be 3.53, not the newer 3.72.** The 2024.2 platform pins Kotlin
   to **1.9**, and JediTerm 3.72 is compiled with Kotlin 2.1 → `compileKotlin`
   fails with *"Module was compiled with an incompatible version of Kotlin
   (metadata 2.1.0, expected 1.9.0)"*. JediTerm **3.53** is built with Kotlin
   1.9.22 and works. We also `exclude(group = "org.jetbrains.kotlin")` so the IDE
   provides the Kotlin stdlib at runtime (no duplicate-class conflicts).
2. **2024.2 bundles no JediTerm at all** (its Gen2 terminal is a different
   implementation), so bundling our own `jediterm-core`/`jediterm-ui` causes **no
   classloader conflict** — we are free to ship it.

### Repositories

`build.gradle.kts` adds `mavenCentral()`, the **JetBrains dependencies
cache-redirector** (`https://cache-redirector.jetbrains.com/intellij-dependencies`
— the only place JediTerm is published), and the IntelliJ Platform default
repositories.

---

## 3. Architecture at a glance

```
                          ┌──────────────────────────────────────────┐
   plugin.xml  ──────────▶│  Extensions registered with the platform │
                          │  • toolWindow "SSH Sessions"  (left)      │
                          │  • toolWindow "SSH Terminals" (bottom)    │
                          │  • fileEditorProvider (terminal-in-tab)   │
                          │  • applicationConfigurable (colors)       │
                          │  • notificationGroup "HaifaSSH"           │
                          └──────────────────────────────────────────┘

   UI (Swing)                 Domain / persistence              SSH + Terminal
   ──────────                 ────────────────────              ──────────────
   SshSessionsPanel  ──uses──▶ SshSessionService (APP)   ┌────▶ SshConnector (JSch)
     tree + toolbar            └ SshSessionState (XML)    │       └ jump-host port-forward chain
     + context menu            SshCredentialStore ────────┘       SshShellHandle (PTY + sessions)
     + Shift+F6 rename           (PasswordSafe)                         │
     + speed search            HaifaSshSettings (APP, XML)              ▼
   SshSessionDialog           SshSessionTransfer/Export          SshTtyConnector  ⇄  JediTerm
   JumpHostDialog               (import/export bundle)           IdeTerminalSettingsProvider
   ExportOptionsDialog                                          ThemedJediTermWidget
   TerminalAppearanceConfigurable ◀── terminal/theme/* (schemes, palette, colors)
   SessionIcons / AppearanceColors (tree appearance)

                 SshTerminalManager (PROJECT) ── orchestrates connect → open
                   ├─ open as editor tab  → SshTerminalVirtualFile → SshTerminalFileEditor(Provider)
                   └─ open in tool window → SshTerminalUi.createWidget(...)
```

Packages (under `com.haifasoft.haifassh`):

- **`model/`** — plain serializable beans: `SshSession`, `SshFolder`, `JumpHost`,
  `SshAuthType`.
- **`settings/`** — persistence & secrets: `SshSessionService` (the tree),
  `SshSessionState`, `HaifaSshSettings` (prefs + custom scheme),
  `SshCredentialStore` (PasswordSafe), `SshSessionTransfer` + `SshSessionExport`
  (import/export bundle), `ExportCrypto` (AES-GCM encryption of with-secrets exports).
- **`ssh/`** — transport: `SshConnector` (JSch + jump-host chaining), `HopConfig`
  (a resolved hop), `SshShellHandle` (live PTY + session chain).
- **`terminal/`** — emulator integration: `SshTerminalManager` (orchestration),
  `SshTtyConnector` (JediTerm ⇄ SSH bridge), `SshTerminalUi`,
  `ThemedJediTermWidget`, `IdeTerminalSettingsProvider`, the bottom tool-window
  factory, `editor/*` (terminal-as-editor-tab), and `theme/*` (color schemes).
- **`ui/`** — all the Swing: the tree panel, dialogs, the color settings page, and
  the new appearance helpers (`SessionIcons`, `AppearanceColors`).

---

## 4. How the platform concepts show up here

| Platform concept (doc 01) | Where it lives in this plugin |
|---------------------------|------------------------------|
| `@Service(APP)` + `PersistentStateComponent` | `SshSessionService` (session tree → `haifassh-sessions.xml`), `HaifaSshSettings` (prefs → `haifassh-settings.xml`). |
| `@Service(PROJECT)` | `SshTerminalManager` — terminals are per-project. |
| `PasswordSafe` | `SshCredentialStore` — every secret keyed by `HaifaSSH/<sessionId>/…`. |
| `ToolWindowFactory` | `SshSessionsToolWindowFactory` (left), `SshTerminalsToolWindowFactory` (bottom, starts empty). |
| `FileEditorProvider` | `SshTerminalFileEditorProvider` → accepts only `SshTerminalVirtualFile`, opens `SshTerminalFileEditor`. |
| `Configurable` | `TerminalAppearanceConfigurable` (*Settings → Tools → HaifaSSH Terminal*). |
| `AnAction` / `DumbAwareAction` / `ToggleAction` | All toolbar + context-menu actions in `SshSessionsPanel`. |
| `ToolbarDecorator` | The toolbar around the session tree. |
| `TreeSpeedSearch` | Type-to-find in the tree. |
| Custom `TreeCellEditor` | In-place Shift+F6 rename. |
| `Task.Backgroundable` | `SshTerminalManager.connect` — the SSH handshake runs off the EDT with a progress bar. |
| Message bus `Topic` | `SshSessionService.TOPIC` (tree changed → panels reload); `HaifaSshSettings.SCHEME_TOPIC` (scheme changed → terminals repaint); subscribes to platform `LafManagerListener.TOPIC` (theme switch → re-theme terminals). |
| `Disposable` scoping | Terminal widgets/listeners tied to the editor (`FileEditor` is a `Disposable`) or to a per-tab `Disposer.newDisposable(...)`. |
| `IconLoader` + monochrome New-UI icons | Tool-window stripe icons; tintable session icons. |

---

## 5. Key flows end-to-end

### Connecting to a session

1. User double-clicks a session (or the ▶ action) in `SshSessionsPanel`.
2. `SshTerminalManager.connect(session)` runs on the EDT: it calls `buildChain`,
   which resolves every hop's secrets from `SshCredentialStore`. If a password is
   needed but not stored, it **prompts now, on the EDT** (`Messages.showPasswordDialog`).
3. The actual handshake runs in a **`Task.Backgroundable`** (off the EDT):
   `SshConnector.openShell(chain, "xterm-256color", 80, 24)` opens each hop,
   chaining through jump hosts with **nested local port forwarding**, then opens a
   `shell` channel with a PTY → returns an `SshShellHandle`.
4. Back on the EDT (`onSuccess`), the handle is presented as either an **editor
   tab** (`SshTerminalVirtualFile` → `FileEditorManager.openFile`) or a
   **tool-window tab** (`SshTerminalUi.createWidget`), per `HaifaSshSettings.openInEditor`.
5. A `SshTtyConnector` bridges the JediTerm widget to the SSH PTY streams.

### Jump hosts (ProxyJump)

`SshConnector` reaches hop *n* by opening a `setPortForwardingL("127.0.0.1", 0,
nextHost, nextPort)` tunnel on hop *n-1*'s session, then connecting to
`127.0.0.1:<ephemeralPort>`. The forward binds to **loopback only**, so it is not
exposed to other machines. Sessions are torn down in reverse order on close or
failure.

### Terminal theming

`IdeTerminalSettingsProvider` feeds JediTerm the **active** `TerminalColorScheme`
on every call, so a repaint immediately reflects a new theme. The scheme is one
of: a built-in template, the user's editable **Custom** scheme (persisted as hex
strings in `HaifaSshSettings.StoredScheme`), or **Follow IDE Theme** (derived live
from the IDE's editor colors). `ThemedJediTermWidget` also seeds JediTerm's
*default cell style* from the scheme so even pre-SGR output (e.g. the MOTD) uses
the right colors. `SchemeColorPalette` supplies the full xterm 256-color palette
(16 from the scheme, 16–255 computed as the standard cube + grayscale ramp).

### Persistence & secrets split

- Non-secret metadata (host, port, user, auth type, key path, jump hosts, icon,
  color) → `SshSessionService` → `haifassh-sessions.xml` via the XML serializer.
- Secrets (passwords, passphrases, per-jump-host secrets) → `SshCredentialStore`
  → PasswordSafe, keyed by session id. They never enter the XML.
- **Export with secrets:** opt-in, and the bundle is **encrypted** with a user
  passphrase (AES-256-GCM + PBKDF2, see `ExportCrypto`); the file is also tightened
  to owner-only permissions. Config-only exports stay plain XML.

---

## 6. Non-obvious implementation decisions (and why)

- **Model classes are mutable beans with defaults**, not idiomatic immutable
  Kotlin. Required by the platform XML serializer (doc 01 §3).
- **Per-session icons are flat monochrome SVGs**, tinted at render time with
  `IconUtil.colorize`. Flat single-color shapes tint predictably; the default tint
  is a blue (`AppearanceColors.DEFAULT_SESSION_TINT`) so default sessions don't
  look gray.
- **In-place rename uses a custom `TreeCellEditor` whose `isCellEditable` returns
  true only for a `null` event.** That means editing starts *only* programmatically
  (Shift+F6 / context menu), never on a click — so double-click-to-connect still
  works. The rename is committed via an overridden `DefaultTreeModel.valueForPathChanged`
  that renames through the service (instead of replacing the node's user object
  with a raw string), deferred with `invokeLater` to avoid re-entrant model
  rebuilds during Swing's edit-completion.
- **Tool window icons** were redrawn as clean monochrome `#6C707E` SVGs. The old
  `#6E6E6E` stroke-based icons rendered **dimmed/gray** in the New UI because the
  platform's stripe-icon recoloring didn't adapt them like the bundled icons.
- **No `untilBuild` cap.** This is distributed internally across rolling IDE
  upgrades; a cap would block each new IDE release. The trade-off is no automatic
  protection against a future breaking platform change.
- **`SshTerminalVirtualFile` extends `com.intellij.testFramework.LightVirtualFile`.**
  `LightVirtualFile` lives in the `testFramework` package but is widely used in
  production for in-memory virtual files; it's a pragmatic choice, with the caveat
  that it's a less-guaranteed API surface.
- **Legacy SHA-1/DSA algorithms (`ssh-rsa`, `ssh-dss`) are opt-in per session**
  (`SshSession.allowLegacyAlgorithms`, off by default). The maintained JSch fork
  drops them and some old appliances still only offer them, so the user can enable
  the fallback for a specific host; modern hosts keep a strict set. Secure-by-
  default compatibility escape hatch (security report F2).
- **Host keys are verified (TOFU).** `StrictHostKeyChecking=ask` + an EDT prompt;
  each hop verified against its real identity via `HostKeyAlias` so tunneled jump
  hosts verify correctly (security report F1). A `CompositeHostKeyRepository` reads
  both the user's `~/.ssh/known_hosts` (prioritized) and a plugin-managed file;
  new acceptances are written only to the plugin file (the system file is never
  modified).
- **Terminal scrollback is configurable** (`HaifaSshSettings.terminalBufferMaxLines`,
  surfaced in the settings page and read by `IdeTerminalSettingsProvider.getBufferMaxLinesCount`).
  Raise it to keep large outputs (e.g. `tail -f -n 10000`) fully scrollable; it
  applies to terminals opened after the change.
- **Connection secrets are wipeable bytes.** `HopConfig` carries `ByteArray`
  password/passphrase that `SshConnector` zeroes after the chain is up; dialogs
  zero the password fields' `char[]` (security report F4, partial by design).

---

## 7. Building & running

```bash
./gradlew buildPlugin   # → build/distributions/haifassh-<version>.zip
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew compileKotlin # quick compile check
```

Install the ZIP via *Settings → Plugins → ⚙ → Install Plugin from Disk…* (pick the
ZIP itself; do not unzip). `buildSearchableOptions` prints harmless headless-IDE
warnings (marketplace fetch, `WindowMove`, JCEF) — they do not affect the artifact.
Note the system Gradle 5.6.4 cannot run on JDK 17; always use the wrapper (8.10).
