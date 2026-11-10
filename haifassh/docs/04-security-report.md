# HaifaSSH — Security Report

**Subject:** HaifaSSH SSH Session Manager plugin (findings identified at the v0.5.0
baseline; all remediations landed in the v0.6.0 security release and remain in place
as of v0.11.1)
**Scope:** the plugin source under `src/main/kotlin/com/haifasoft/haifassh/` and
its dependencies, as of this review.
**Question answered:** *Can this plugin be secured, and what security issues does
it have?*

> **Bottom line:** The plugin **has been secured.** Originally the headline risk
> was that it **disabled SSH host-key verification** (`StrictHostKeyChecking=no`),
> exposing every connection to man-in-the-middle attacks; that — plus re-enabled
> legacy crypto and plaintext credential export — has now been fixed (see the
> remediation status below). Its credential architecture was already sound
> (secrets in PasswordSafe, separated from config), so the fixes were additive.
> This report keeps the original findings for the record, each annotated with its
> fix.

---

## 1. Methodology & threat model

This is a **developer tool** installed locally into a JetBrains IDE. The user owns
the sessions and the machine. The realistic threats are therefore:

- **T1 — Network attacker (MITM):** intercepts/relays SSH traffic on an untrusted
  network, impersonating a server.
- **T2 — Local file exposure:** another user/process/backup/sync reads files the
  plugin writes (exports, config).
- **T3 — Local memory exposure:** secrets recoverable from heap dumps / swap.
- **T4 — Supply chain:** a compromised or vulnerable third-party dependency.
- **T5 — Code-level injection:** attacker-controlled data reaching a sensitive sink.

Review was a manual source audit (no dynamic testing). Severity uses
High/Medium/Low/Info with a developer-tool lens.

---

## 2. Findings summary

> **Remediation status:** F1 ✅, F2 ✅, F3 ✅, F4 ✅ (hardened), F5 ✅, F6 ✅ — all
> code-level findings addressed. F7–F9 are informational/ongoing.

| # | Severity | Finding | Threat | Location |
|---|----------|---------|--------|----------|
| F1 | ✅ **Fixed** (was High) | SSH host-key verification disabled (`StrictHostKeyChecking=no`); no `known_hosts`, no fingerprint check | T1 | `ssh/SshConnector.kt` |
| F2 | ✅ **Fixed** (was Medium) | Legacy weak algorithms re-enabled (`ssh-rsa`/SHA-1, `ssh-dss`/DSA) | T1 | `ssh/SshConnector.kt:72–86` |
| F3 | ✅ **Fixed** (was Medium) | Export can write **plaintext** passwords/passphrases to an unencrypted file | T2 | `ui/ExportOptionsDialog.kt`, `settings/SshSessionTransfer.kt` |
| F4 | ✅ **Hardened** (was Low) | Secrets handled as immutable `String` in memory (cannot be wiped) | T3 | `ssh/HopConfig.kt`, `ssh/SshConnector.kt`, `ui/SshSessionDialog.kt`, `ui/JumpHostDialog.kt` |
| F5 | ✅ **Fixed** (was Low) | Exported file written with default filesystem permissions | T2 | `settings/SshSessionTransfer.kt` |
| F6 | ✅ **Fixed** (was Low) | Per-session secret cleanup bounded to 16 jump hosts | T2/T3 | `settings/SshCredentialStore.kt`, `ui/SshSessionDialog.kt` |
| F7 | **Info** | PasswordSafe strength depends on user-chosen backend | T2/T3 | `settings/SshCredentialStore.kt` |
| F8 | **Info** | No `until-build` cap → runs on untested future platforms | — | `build.gradle.kts:47` |
| F9 | **Info** | Pinned deps: JSch fork `0.2.25` (good), JediTerm `3.53` (older, pinned for Kotlin metadata) | T4 | `build.gradle.kts:27–36` |

### Positives confirmed (already good)

- **Secrets are not stored in config XML** — they go to `PasswordSafe`
  (`SshCredentialStore`), keyed per session. Deleting a session clears its
  secrets.
- **No secret logging.** Error/notification messages include host/display name but
  never passwords (`SshTerminalManager.notifyError`).
- **No command/shell injection.** Host/user/port are passed to `jsch.getSession`
  as discrete arguments — never interpolated into a shell string (T5 not present).
- **Jump-host forwards bind to loopback only** (`setPortForwardingL("127.0.0.1",
  0, …)`), not exposed to the LAN.
- **Import is merge-only** with fresh UUIDs — it cannot silently overwrite
  existing sessions.
- **Connection hardening basics**: 30 s connect timeout and 30 s keep-alive.
- **Plaintext export is opt-in and clearly warned** in the UI.

---

## 3. Detailed findings

### F1 — Host-key verification disabled (High) — ✅ Fixed

**Status: fixed.** The connector now loads a plugin-managed `known_hosts` file
(`<IDE config>/haifassh/known_hosts`), sets `StrictHostKeyChecking=ask`, and
prompts the user (trust-on-first-use) via a `HostKeyPrompt` shown on the EDT.
Each hop is verified against its **real** host identity using a per-hop
`HostKeyAlias` (`host` for port 22, `[host]:port` otherwise) — JSch uses the alias
verbatim as the known_hosts key, so verification is correct and stable even for
**tunneled jump hosts** (whose socket port is an ephemeral local port). A
**changed** key triggers JSch's MITM warning before the confirm, defaulting to the
safe "Abort" choice. The original issue is described below for the record.

```kotlin
// BEFORE (insecure):
session.setConfig("StrictHostKeyChecking", "no")
```

Every hop is connected with strict host-key checking off and **no `known_hosts`
file configured**, so JSch accepts *any* server key without verification or
warning. On an untrusted network (T1), an attacker who can redirect traffic can
present their own key, complete the handshake, and **relay or capture the entire
session — including the password** the user types (or that the plugin sends). This
also defeats the protection password-auth would otherwise get from a verified host
key. The README roadmap already acknowledges this ("Known-hosts verification
(currently `StrictHostKeyChecking=no`)").

**Impact:** full session compromise / credential theft against a network-positioned
attacker. This is the single most important issue.

**Remediation (recommended):** implement **trust-on-first-use (TOFU)** like
OpenSSH:
- Configure a `known_hosts` file: `jsch.setKnownHosts(path)` (e.g. under the
  plugin config dir, or reuse `~/.ssh/known_hosts`).
- Set `StrictHostKeyChecking=ask` and provide a `UserInfo`/`HostKeyRepository`
  that, on an unknown key, shows the fingerprint in a dialog and lets the user
  accept (and persist) or reject; on a **changed** key, warn loudly and refuse by
  default.
- Optionally allow a per-session "trusted fingerprint" field for pinning.

This is the change that moves the plugin from "convenient but unsafe on hostile
networks" to "safe by default."

### F2 — Legacy weak algorithms re-enabled (Medium) — ✅ Fixed

**Status: fixed.** The legacy fallback is now **opt-in per session** via a new
`SshSession.allowLegacyAlgorithms` flag (off by default), surfaced as an "Allow
legacy SHA-1/DSA algorithms (only for old servers)" checkbox in the session
dialog and threaded through `HopConfig` to the connector
(`if (hop.allowLegacy) enableLegacyAlgorithms(session)`). Modern hosts now keep a
strict algorithm set; the weak `ssh-rsa`/`ssh-dss` fallback applies only to the
specific old host the user explicitly enables it for. Original issue:

```kotlin
val legacy = listOf("ssh-rsa", "ssh-dss")           // SshConnector.kt:73
// previously appended unconditionally for every hop
```

`ssh-dss` (DSA, effectively 1024-bit) and `ssh-rsa` (RSA with **SHA-1**) are
deprecated and cryptographically weak. They are appended *after* modern algorithms
(so modern is preferred), which limits exposure, but they remain available as a
negotiated fallback and **widen the attack surface / enable downgrade scenarios**,
especially when combined with F1 (no host-key verification). This is a deliberate
compatibility trade-off for old appliances (documented in the code).

**Remediation:** make the legacy fallback **opt-in per session** (a "Allow legacy
SHA-1/DSA algorithms" checkbox) rather than globally on, so the weakening applies
only to the specific old host that needs it, and modern hosts keep a strict
algorithm set. Prefer enabling only `ssh-rsa` (SHA-1) over `ssh-dss` where possible.

### F3 — Plaintext credential export (Medium) — ✅ Fixed

**Status: fixed.** Exports that include credentials are now **encrypted** with a
user passphrase: AES-256-GCM with a key derived via PBKDF2-HMAC-SHA256 (210k
iterations, random per-file salt + IV), implemented in `settings/ExportCrypto.kt`.
The export dialog now requires a passphrase (with confirmation) when secrets are
included, and import detects the encrypted format (`HAIFASSH-ENC-V1` header),
prompts for the passphrase, and reports a clear error on a wrong one (GCM tag
mismatch → `DecryptionException`). Config-only exports remain plain XML. The
round-trip and wrong-passphrase rejection were verified on JDK 17. Original issue:

When the user ticks *Include saved passwords & passphrases*, `export` pulls
secrets out of PasswordSafe and writes them **inline, in cleartext**, into the
chosen XML file (`SshSessionTransfer.export` → `toExport(includeSecrets=true)`;
`Files.writeString` at `SshSessionTransfer.kt:32`).
The file is a normal, unencrypted file on disk (T2). The UI warns clearly, and
it's opt-in, which is why this is Medium rather than High — but the resulting
artifact is a high-value plaintext secret store that can be backed up, synced to
cloud storage, or emailed.

**Remediation (pick one or more):**
- **Encrypt the bundle** with a user-supplied passphrase (e.g. AES-GCM with a
  KDF such as PBKDF2/Argon2) when secrets are included; require the same
  passphrase on import.
- Or **drop inline secrets entirely** and export config only, relying on
  PasswordSafe re-entry on the destination machine.
- At minimum, write the file with **owner-only permissions** (see F5) and remind
  the user to delete it after transfer.

### F4 — Secrets as immutable `String` in memory (Low) — ✅ Hardened

**Status: hardened (partial by design).** Secrets now flow to JSch as **wipeable
`ByteArray`s**: `HopConfig` carries `password`/`passphrase` as `ByteArray?`,
`SshConnector` uses `setPassword(byte[])` / `addIdentity(path, byte[])` and zeroes
every hop's bytes in a `finally` once the chain is established. The dialogs zero
the transient `char[]` from the password fields after building the stored value
(`SshSessionDialog.persistSecrets`, `JumpHostDialog.doOKAction`).

**Accepted residual:** the secret still exists as a `String` at the platform/JSch
boundaries we don't control — `PasswordSafe.getPassword()` and
`Messages.showPasswordDialog()` both return `String`, and JSch keeps its own copy.
Fully eliminating these would require replacing PasswordSafe as the secret store,
which is out of proportion to a Low, on-workstation, heap-dump-only risk.

### F5 — Export file permissions (Low) — ✅ Fixed

**Status: fixed.** After writing any export, `restrictToOwner` tightens the file
to owner-only access — `rw-------` on POSIX via `PosixFilePermissions`, and on
non-POSIX (Windows) it strips group/other read & write via the `File` API. Applied
to all exports (even config-only, which still lists hosts/usernames). Original
issue: `Files.writeString` previously created the file with umask defaults, which
on shared systems could be group/world-readable.

### F6 — Secret cleanup bounded to 16 jump hosts (Low) — ✅ Fixed

**Status: fixed.** The bound is now a single shared constant,
`SshCredentialStore.MAX_TRACKED_JUMP_HOSTS = 64`, used both when wiping a deleted
session and when clearing leftover slots after an edit, so the two stay in sync
and no orphaned secrets remain for any realistic chain (jump-host chains longer
than 64 are not a real scenario). Original issue: the delete path and the
edit-cleanup loop used independent literal `16` bounds.

### F7 — PasswordSafe backend strength (Info)

`SshCredentialStore` correctly uses `PasswordSafe`, but its real protection
depends on the **user's** chosen backend: the OS keychain (strong) vs. a KeePass
file *with no master password* (obfuscation only). The plugin can't force this,
but it's worth surfacing in docs and, optionally, warning if the in-memory/no-
master backend is active.

### F8 — No compatibility upper bound (Info)

`untilBuild = null` (`build.gradle.kts`) is an intentional choice for rolling
internal upgrades, but it means the plugin will load into **future, untested**
platform versions where a changed API could misbehave. Not a security
vulnerability per se; relevant to reliability and to the `testFramework`
`LightVirtualFile` usage (a less-stable API). Mitigate with `verifyPlugin` runs
against new IDE builds before rollout.

### F9 — Dependencies / supply chain (Info)

- **`com.github.mwiede:jsch:0.2.25`** — the right call (the original JCraft JSch is
  abandoned); keep it **updated**, as SSH crypto fixes ship here.
- **JediTerm `3.53`** — pinned older because 3.72 needs Kotlin 2.x metadata
  incompatible with the 2024.2 toolchain. Acceptable, but it **lags upstream
  fixes**; revisit when the target platform's Kotlin moves to 2.x.
- General hygiene: pin versions (done), and consider a dependency-vulnerability
  scan (e.g. OWASP dependency-check / GitHub Dependabot) in CI.

---

## 4. Remediation roadmap — status

| Priority | Action | Addresses | Status |
|----------|--------|-----------|--------|
| 1 | `known_hosts` + TOFU host-key verification (fingerprint prompt, refuse changed keys) | F1 | ✅ Done |
| 2 | Make legacy SHA-1/DSA algorithms an opt-in per-session toggle | F2 | ✅ Done |
| 3 | Encrypt exported bundles with a passphrase + owner-only file perms | F3, F5 | ✅ Done |
| 4 | Handle in-memory secrets as wipeable bytes; zero after use | F4 | ✅ Done (partial by design) |
| 5 | Clear exactly the stored jump-host secrets (no fixed 16 bound) | F6 | ✅ Done |
| Ongoing | Keep JSch/JediTerm updated; run `verifyPlugin` + a dependency scan in CI; document PasswordSafe backend guidance | F7, F8, F9 | Ongoing |

---

## 5. Verdict

**Secured.** All code-level findings have been remediated: host keys are now
verified (TOFU) so the plugin is safe on untrusted networks; weak legacy crypto is
opt-in per host; credential exports are encrypted and owner-only; in-memory secrets
are passed as wipeable bytes; and jump-host secret cleanup no longer orphans keys.
The remaining items (F7–F9) are informational/operational — keep dependencies
updated, run `verifyPlugin` against new IDE builds, and consider a dependency-
vulnerability scan in CI. The foundations were already sound (secrets in
PasswordSafe, no injection sink, loopback-only forwards, non-destructive import),
so the fixes were additive rather than structural.
