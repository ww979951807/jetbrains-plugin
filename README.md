# jetbrains-plugin

Standalone IntelliJ plugins, each built by its own GitHub Actions workflow
(artifact: `<project>-plugin`).

| Project | Description |
|---|---|
| `claude` | Claude Sessions tool window ¡ª session list for the current project, double click resumes, green dot for open tabs |
| `script` | Script Runner tool window ¡ª script/command configurations, double click runs in a terminal tab |
| `haifassh` | HaifaSSH with a CJK/Chinese rendering fix (IDE console font + CJK fallback) |

claude/script require the terminal plugin (reworked engine supported via the
frontend tab API); they are built against IntelliJ IDEA Community 2026.2.
