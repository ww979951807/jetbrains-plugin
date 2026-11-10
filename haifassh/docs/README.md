# HaifaSSH Documentation

Start here. Read in order for a full picture, or jump to what you need.

1. **[IntelliJ IDEA Plugin System](01-intellij-plugin-system.md)** — how JetBrains
   plugins work in general (services, extensions, threading, the message bus,
   PasswordSafe, the Gradle build). Read this first if plugin development is new
   to you.
2. **[How HaifaSSH Is Implemented](02-implementation.md)** — what this plugin does,
   its technology stack, architecture, key flows, and the non-obvious decisions.
3. **[Code Guide](03-code-guide.md)** — a file-by-file walkthrough so you can read
   the source and understand every piece.
4. **[Security Report](04-security-report.md)** — threat model, findings (with
   severities and locations), a prioritized remediation roadmap, and the verdict
   on whether the plugin can be secured.
5. **[Publishing to the JetBrains Marketplace](05-publishing-to-marketplace.md)** —
   step-by-step release guide: build the zip, upload, moderation, listing media,
   and the Gradle fast-path for future updates.

For build/install/usage instructions, see the top-level [README](../README.md).
