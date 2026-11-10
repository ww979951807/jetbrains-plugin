# HaifaSSH — SSH, SFTP & FTP Session Manager for JetBrains IDEs

A fast, tabbed SSH, SFTP, and FTP session and file manager embedded in IntelliJ-based IDEs:
organize servers in a folder tree, connect to interactive terminals, browse and transfer remote
files, and keep credentials in the IDE Password Safe. Free and open source (MIT).

[![Build](https://github.com/AdelinGhanaem/HaifaSSH/actions/workflows/build.yml/badge.svg)](https://github.com/AdelinGhanaem/HaifaSSH/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Built by HaifaSoft](https://img.shields.io/badge/Built%20by-HaifaSoft-0b1222)](https://haifasoft.com/)

> **📚 Documentation:** see [`docs/`](docs/README.md) — the IntelliJ plugin
> system, how this plugin is implemented, a file-by-file code guide, and a full
> security report.

## Features (v0.16.2)

- **Session tree** — folders + sessions in the left-hand *SSH Sessions* tool window.
- **Customizable appearance** — give each session a built-in **icon** (server, database,
  cloud, terminal, globe, container, laptop, secure) and a **color**; color folders too.
  The color tints both the icon and the name in the tree. Set it from the session dialog,
  or right-click → *Set Color…* / *Reset Color* (works for folders and sessions).
- **Multi-select bulk edits** — Ctrl/Shift-click to select several sessions (or several
  folders) and change them together: set the same color, set the same icon (sessions),
  or delete them all at once. A selection stays to one kind for unambiguous bulk actions.
- **Duplicate a session** — right-click → *Duplicate…* opens a new-session dialog with
  every field pre-filled (host, port, auth, key, jump hosts, icon/color, and the saved
  password/passphrase), so you can tweak one detail and save it as a new connection.
- **Build the tree from a folder** — right-click a folder for *New Session Here* and
  *New Subfolder*; both create the new item directly inside that folder.
- **Drag & drop** — drag sessions and folders into (or out of) any folder to reorganize
  the tree; drop *between* two rows instead of *on* one to set the order yourself. The
  manual order sticks, and lists you never rearrange stay alphabetical.
- **In-place rename** — press **Shift+F6** on any folder or session to rename it right in
  the tree, just like renaming a symbol; **Esc** cancels.
- **Quick search** — start typing in the tree to find and jump to a session/folder by name.
- **Embedded SSH terminals** — double-click an SSH session (or use the ▶ toolbar button)
  to open a real interactive shell (powered by
  [JediTerm](https://github.com/JetBrains/jediterm) + JSch).
- **SSH, SFTP, and FTP protocols** — define sessions as **SSH**, **SFTP**, or **FTP**.
  Double-clicking an SFTP or FTP session opens directly into the file browser without launching
  an SSH shell. An "Open SSH Terminal" button is readily available in the file browser whenever SSH is supported.
- **Embedded SFTP / FTP file browser** — navigate remote directories, create folders, rename,
  delete, and download files directly into the IDE.
- **Side-by-side local filesystem explorer** — open your local file explorer side-by-side with
  active SFTP/FTP sessions in a resizable split view. Explore local folders and drives, select
  multiple files, and drag-and-drop them directly into remote directories to upload.
- **IntelliJ Terminal-style View Modes & Docking** — customize how the local explorer is displayed:
  *Docked Pinned* (side-by-side split), *Docked Unpinned*, or detach into a floating
  *Window / Float / Undock* mode. Move the docked panel *Left*, *Right*, *Up*, or *Down* anytime,
  or click *Put back to dock* to return to the session view.
- **File Explorer Type-to-Jump / SpeedSearch** — in both local and remote file browsers, start
  typing a name (e.g. `Down`) to jump selection directly to that file (e.g. `Downloads`) and scroll
  it into view. Supports backspace character deletion, ESC to reset, and keyboard shortcuts (`Alt+Up`, `Alt+Left/Right`).
- **Overhauled toolbar layout with hover feedback** — full-width address bar placed above the
  icons, with responsive rounded hover highlights, hand cursor, and instant tooltips on every button.
- **Drag & drop upload** — drag files directly from your desktop or the IntelliJ Project View and drop
  them into the remote file browser to upload with background progress and cancellation.
- **Terminal Assistant Panel** — dockable companion panel beside live SSH terminals (left, right, top,
  or bottom) with three integrated tabs: **SFTP Files** (browse and transfer host files), **History**
  (auto-recorded executed commands), and **Commands / Snippets** (save reusable, parameterized command snippets
  for 1-click execution).
- **Terminal opens as an editor tab** — by default the shell opens in the editor area,
  right where source files open. Toggle the *Open Terminals in Editor Tab* button in the
  *SSH Sessions* toolbar to fall back to the bottom *SSH Terminals* tool window instead.
  Splitting the editor works normally: a terminal can only run in one split at a time, so
  the other split offers a *Show it here* link to move it over.
- **Terminal color schemes** — pick a ready-made template (Classic Dark, Solarized Dark/Light,
  Monokai, One Dark, Light), **Follow IDE Theme**, or a fully editable **Custom** scheme
  (background, foreground, selection, cursor, and all 16 ANSI colors) under
  *Settings → Tools → HaifaSSH Terminal* (or the palette button in the *SSH Sessions* toolbar).
  Use *Copy to Custom* to start from a template and tweak it.
- **Import / export** — back up or share your whole session tree via the toolbar
  *Import*/*Export* buttons. Configuration (hosts, folders, jump hosts) is always
  exported; passwords/passphrases are included only if you tick the option (the file
  then holds plaintext secrets). Import always *merges* — it never overwrites existing
  sessions.
- **Secure credentials** — passwords and key passphrases live in the IDE
  [Password Safe](https://www.jetbrains.com/help/idea/passwords.html), never in the
  session XML. Untick *Store credentials* to be prompted on each connect.
- **Authentication** — password or private-key auth.
- **Jump hosts** — chain through one or more bastion hosts (ProxyJump-style),
  each with its own credentials.

## Requirements

- JetBrains IDE **2024.2+** (build 242+)
- JDK **17** to build

## Build & run

```bash
# Compile + package the installable plugin zip (build/distributions/*.zip)
./gradlew buildPlugin

# Launch a sandbox IDE with the plugin installed
./gradlew runIde
```

### Installing from disk

After `buildPlugin`, install this file:

```
build/distributions/haifassh-0.16.2.zip
```

In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, then select
that **`.zip`**. Pick the ZIP itself — do **not** unzip it, and do **not** choose
anything under `build/tmp/buildSearchableOptions/` (those are intermediate index
files that are already bundled inside the ZIP).

> The build prints warnings from a headless sandbox IDE during
> `buildSearchableOptions` (marketplace fetch errors, `WindowMove`/JCEF notices,
> etc.). These are harmless and do not affect the produced ZIP.

## Usage

1. Open the **SSH Sessions** tool window (left).
2. Click **+** to add a session (**SSH**, **SFTP**, or **FTP**; host, port, user, auth, optional jump hosts);
   use the folder button to group sessions.
3. **Double-click** a session:
   - **SSH**: opens an interactive terminal in an **editor tab** (or bottom *SSH Terminals* window)
     alongside the companion Assistant panel (SFTP files, History, Snippets).
   - **SFTP / FTP**: opens directly into the embedded remote file browser.
4. **Transfer files**: open the side-by-side local explorer to drag and drop files directly between your machine
   and remote hosts.
5. Use the toolbar **Import**/**Export** buttons to move sessions between machines.

## Architecture

| Layer | Key types |
|-------|-----------|
| Model | `model/SshSession`, `SshFolder`, `JumpHost`, `SessionProtocol`, `CommandSnippet` |
| Persistence | `settings/SshSessionService`, `SshCredentialStore` (PasswordSafe), `HaifaSshSettings`, `SshCommandSnippetService`, `SshCommandHistoryService` |
| Import/export | `settings/SshSessionTransfer`, `SshSessionExport` (portable bundle) |
| SSH & Protocols | `ssh/SshConnector` (JSch + jump-host port forwarding), `SshShellHandle` |
| File System | `fs/RemoteFileSystem`, `RemoteFile`, `sftp/SftpRemoteFileSystem`, `ftp/FtpRemoteFileSystem`, `RemoteFileManager` |
| Terminal | `terminal/SshTtyConnector` (JediTerm ↔ SSH PTY bridge), `SshTerminalManager`, `SshTerminalUi`, `IdeTerminalSettingsProvider` |
| Terminal Assistant | `terminal/assist/TerminalAssistantPanel`, `SshTerminalPane`, `CommandSnippetsTab`, `CommandHistoryTab`, `SftpTab` |
| Terminal theme | `terminal/theme/TerminalColorScheme`, `SchemeColorPalette` (16-color ANSI), `TerminalSchemes` (templates + Follow-IDE) |
| Editor Providers | `terminal/editor/SshTerminalFileEditor(Provider)`, `fs/editor/RemoteFileBrowserFileEditorProvider` |
| UI | `ui/SshSessionsPanel` (tree + toolbar), `fs/ui/RemoteFileBrowserPanel`, `fs/ui/LocalFileExplorerPanel`, `SshSessionDialog`, `JumpHostDialog`, `ExportOptionsDialog`, `TerminalAppearanceConfigurable` |

## Roadmap
 
- [x] SFTP file browser for the active session and standalone SFTP/FTP client
- ssh-agent / `~/.ssh/config` import
- Per-session terminal color schemes & startup commands

Host-key verification (trust-on-first-use against a managed `known_hosts`) is
implemented; see [`docs/04-security-report.md`](docs/04-security-report.md).

## About HaifaSoft

HaifaSSH is built and maintained by **[HaifaSoft](https://haifasoft.com/)** — engineer-led software development, multiplied by AI.

We partner directly with businesses to transform ideas and complex requirements into production-ready software solutions without agency overhead or middle layers:

- 🚀 **Full-Stack & Mobile Development** — scalable web and mobile applications from design to production.
- 🤖 **Agentic & AI Solutions** — production-grade AI agents, LLM integrations, and automated engineering pipelines.
- ☁️ **Cloud, Data & DevOps** — cloud-native architecture (AWS/GCP), streaming/data platforms, and CI/CD automation.
- 🛠️ **Custom Developer Tooling** — high-performance IDE plugins, internal developer tools, and system utilities.

Need engineering firepower, architectural guidance, or custom tooling for your team?  
👉 **[Visit haifasoft.com](https://haifasoft.com/)** or reach out directly at **[contact@haifasoft.com](mailto:contact@haifasoft.com)**.

