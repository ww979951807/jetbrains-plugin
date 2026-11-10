# Publishing HaifaSSH to the JetBrains Marketplace

A step-by-step guide for releasing HaifaSSH to the
[JetBrains Marketplace](https://plugins.jetbrains.com). Tailored to this repo:
a Gradle IntelliJ Platform plugin with `id = com.haifasoft.haifassh`, versioned
from `gradle.properties` (`pluginVersion`).

---

## Step 0 — Pre-flight checks

`plugin.xml` already contains everything the Marketplace requires; confirm before each release:

- **Unique `id`** — `com.haifasoft.haifassh` (this can **never** change once published).
- **`name`, `vendor` (with a valid email), `description`** (>40 chars, not just links),
  and **`change-notes`**.
- **`since-build` = 242**, no upper bound.
- **License** = MIT (`LICENSE` at the repo root).
- A **category** picked (e.g. *Tools Integration*).
- Version bumped in `gradle.properties` (`pluginVersion`) — this is the single source of
  truth; `patchPluginXml` injects it into `plugin.xml` at build time.

## Step 1 — Build the installable zip

```bash
./gradlew clean buildPlugin
```

Produces `build/distributions/haifassh-<version>.zip` (e.g. `haifassh-0.11.1.zip`).
**Upload the zip itself — never an unzipped folder.**

Optional local smoke test:
IDE → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → select the zip →
confirm it loads and works.

## Step 2 — Create your Marketplace / JetBrains account

1. Go to <https://plugins.jetbrains.com>.
2. Sign in (top-right) with a **JetBrains Account** — create one if you don't have it.
   This is only your publisher identity.

## Step 3 — Upload the plugin (first release = via the website)

1. Avatar menu → **Upload plugin** (or <https://plugins.jetbrains.com/plugin/add>).
2. If prompted, create a **Vendor profile** (your name or "HaifaSoft" — shown as the author).
3. **Select the zip** (`build/distributions/haifassh-<version>.zip`).
4. Set:
   - **License** → Open source / **MIT**.
   - **Category** → e.g. *Tools Integration*.
   - **Tags** → `ssh`, `terminal`, `remote`, `sftp` (aids discovery).
5. **Accept the Marketplace Developer Agreement** (a one-time click).
6. Submit.

## Step 4 — Moderation

- **First-time plugins are reviewed by JetBrains** (automated scan + human guideline/malware
  check). This typically takes a **few business days**.
- You'll get an email when it's approved or if changes are requested.

## Step 5 — Once approved: polish the listing

On your plugin page → **Edit**:

- **Screenshots** — upload PNG/JPEG files (the first one becomes the thumbnail).
- **Video** — upload it to **YouTube** (unlisted is fine) and paste the link; it embeds
  as a playable video. The Marketplace does not host raw video files.
- **Links** — source repository (GitHub) and company website ([HaifaSoft](https://haifasoft.com)).
- Verify the description renders correctly.

> Media (screenshots/videos) is **not** part of the zip or `plugin.xml`; it is managed on
> the plugin page after upload. `plugin.xml` may embed images only via absolute public URLs,
> but the dedicated Screenshots section is the cleaner path — keep `plugin.xml` text-only.

## Step 6 — Publishing future updates (fast path)

For version 2+, don't repeat the web flow. Bump the version and either re-upload the new
zip on the site, or publish straight from Gradle:

1. Generate a **permanent token**: Marketplace → your profile → **My Tokens**.
2. Add to `build.gradle.kts`:
   ```kotlin
   intellijPlatform {
       publishing {
           token = providers.environmentVariable("PUBLISH_TOKEN")
       }
   }
   ```
3. Bump `pluginVersion` in `gradle.properties`, then:
   ```bash
   PUBLISH_TOKEN=xxxx ./gradlew publishPlugin
   ```

Updates to an already-approved plugin usually go live within minutes to hours (no full
re-review).

---

## Tips

- Make the **GitHub repo public** before/around approval so README links and screenshots resolve.
- Keep `gradle.properties` as the single source of version truth — just bump `pluginVersion`
  per release.
- Write a clear `change-notes` entry in `plugin.xml` for every release; it's what users see
  in the "What's new" section.
