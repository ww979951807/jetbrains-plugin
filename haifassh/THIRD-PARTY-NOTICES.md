# Third-Party Notices

HaifaSSH is distributed under the [MIT License](LICENSE). It bundles and/or
depends on the following third-party components. Each remains under its own
license, and those licenses continue to apply to the respective components.

The list below is maintained by hand. Before each release, regenerate the
resolved dependency tree with `./gradlew dependencies --configuration runtimeClasspath`
and reconcile any additions here. Always confirm the exact license against each
project's own repository/POM — the summaries below are for convenience only.

---

## Bundled at runtime (shipped inside the plugin distribution)

### JediTerm (`org.jetbrains.jediterm:jediterm-core`, `org.jetbrains.jediterm:jediterm-ui`) — v3.53
- Terminal emulator widget used to render the embedded SSH shells.
- **License: GNU Lesser General Public License v3.0 (LGPL-3.0)**
- Source: https://github.com/JetBrains/jediterm
- Compliance notes:
  - JediTerm is used **as an unmodified, separately packaged library** (a normal
    dependency JAR — not shaded, relocated, or statically linked into HaifaSSH's
    own classes). Users are free to replace it with their own build of the same
    library.
  - The full text of the LGPL-3.0 (and the GPL-3.0 it references) must be
    distributed with the plugin — see `licenses/LGPL-3.0.txt`.
  - We do not modify JediTerm's source. Its source is available at the URL above,
    tagged at the version listed here.

### JSch — actively maintained fork (`com.github.mwiede:jsch`) — v0.2.25
- SSH client used to establish connections, authentication, and channels.
- Drop-in fork of JCraft JSch (same `com.jcraft.jsch` package).
- **License: BSD-style (revised BSD, 3-clause) — the original JSch license.**
- Source: https://github.com/mwiede/jsch
- Compliance notes: the copyright notice and BSD license text must be reproduced
  in distributions — see `licenses/JSch-BSD.txt`.

---

## Compile-time / provided by the host IDE (not redistributed by us)

### IntelliJ Platform SDK
- HaifaSSH is built against the IntelliJ Platform APIs. These are provided by the
  host JetBrains IDE at runtime and are **not** redistributed inside the plugin.
- The IntelliJ Platform community APIs are primarily under the Apache License 2.0.
- Source / licensing: https://github.com/JetBrains/intellij-community

### Kotlin standard library
- Provided by the IDE at runtime (`kotlin.stdlib.default.dependency=false`; the
  Kotlin dependency is excluded from the JediTerm artifacts). Not redistributed
  by this plugin.
- License: Apache License 2.0 — https://github.com/JetBrains/kotlin

---

## Trademarks
"IntelliJ", "IntelliJ IDEA", and "JetBrains" are trademarks of JetBrains s.r.o.
HaifaSSH is an independent, community plugin and is not affiliated with,
endorsed by, or sponsored by JetBrains. All other product names and trademarks
are the property of their respective owners.
