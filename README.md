# APK Webhook

> One-click **build + upload + Chat-notify** for Android projects, right from your IDE.

A JetBrains plugin (Android Studio / IntelliJ IDEA) that collapses the manual "switch branch → build → find APK → upload somewhere → paste link in chat" workflow into a single toolbar click. Built so QA, Product, and sales teams can get fresh test builds without bothering an engineer.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_Platform-251%2B-000000?logo=intellij-idea&logoColor=white)](https://plugins.jetbrains.com/docs/intellij/)
[![Gradle](https://img.shields.io/badge/Gradle-8.13-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Why this plugin exists

QA, Product, sales, and design teams need install links to in-development APKs many times a day. The manual flow looks like:

1. Stash whatever you're working on.
2. Switch to the requested branch.
3. Run a Gradle assemble task.
4. Locate the APK in `app/build/outputs/apk/...`.
5. Upload it somewhere (S3, Google Drive, an internal sharing server).
6. Paste the install link into team chat with the right context.

This plugin collapses all six steps into **one click** without disturbing your working tree.

## Features

- **Build any branch without a checkout** — uses a git worktree under `~/.cache/apk-webhook/` so your working tree stays untouched. If you're already *on* the requested branch, it builds in place so your uncommitted changes are included.
- **Type-to-filter branch picker** — same UX as Android Studio's native "Git Branches" popup.
- **Build-variant picker** — auto-discovered from the IDE's Gradle sync; falls back to a configured task list if sync data is unavailable.
- **Live build console** — Gradle stdout/stderr stream into a dedicated "APK Share Build" tool window in real time.
- **APK upload with progress bar** — chunked OkHttp upload with byte-accurate progress reported to the IDE status bar.
- **Google Chat notification** — posts a `cardsV2` card with branch, commit, **who shared it**, an optional message, JIRA ticket, and filename, plus clickable install + download URLs.
- **Editable commit changelog** — the dialog pre-fills a "Changes" field with the commits unique to the selected branch (merge commits excluded, base auto-detected from `origin/HEAD`). Edit it freely before sending; it renders as its own "Changes" section on the card.
- **Optional multi-line message** — a free-text note (what to test, caveats), separate from the changelog.
- **JIRA auto-extract** — pulls the parent ticket ID from your branch name (`feature/PROJ-100-proj-99-some-slug` → `PROJ-99`). Manual edits stick.
- **Secrets stay secret** — your Chat webhook URL lives in IntelliJ's PasswordSafe, not in plain XML.

## How it works

```
 ┌───────────────────┐   ┌───────────────────┐   ┌───────────────────┐
 │ Pick branch +     │ → │ Sync git worktree │ → │ Run Gradle task   │
 │ variant + message │   │ (or build in      │   │ (Tooling API)     │
 │                   │   │ place if same     │   │                   │
 │                   │   │ branch as HEAD)   │   │                   │
 └───────────────────┘   └───────────────────┘   └───────────────────┘
                                                           │
                                                           ▼
 ┌───────────────────┐   ┌───────────────────┐   ┌───────────────────┐
 │ Post Chat card    │ ← │ Upload APK to     │ ← │ Locate newest     │
 │ with install URL  │   │ your APK server   │   │ *.apk             │
 └───────────────────┘   └───────────────────┘   └───────────────────┘
```

The plugin never bundles your infrastructure. You provide:
- The base URL of an "APK server" (any HTTP service that accepts the [upload contract](#server-contract) below).
- A Google Chat incoming-webhook URL.

## Requirements

- Android Studio 2024.2+ (build 251+) or IntelliJ IDEA Ultimate 2024.2+.
- JDK 17 (auto-fetched by the Gradle toolchain plugin).
- An **APK server** that accepts `POST /api/builds/upload?platform=android` — see [Server contract](#server-contract). It's a couple hundred lines of Express / FastAPI / your-stack-of-choice to stand one up.
- A **Google Chat incoming webhook** URL for your team's space (`https://chat.googleapis.com/v1/spaces/...`).

## Install

### From source

```bash
git clone https://github.com/atishagrawal/android-studio-apk-sharing-plugin.git
cd android-studio-apk-sharing-plugin
./gradlew buildPlugin
```

The built ZIP lands at `build/distributions/apk-webhook-<version>.zip`. In Android Studio:
**Settings → Plugins → gear icon → Install Plugin from Disk… → pick the ZIP → restart.**

### From a released ZIP

Track [Releases](https://github.com/atishagrawal/android-studio-apk-sharing-plugin/releases) (coming soon).

## Configure

After install, open **Settings → Tools → APK Webhook**:

| Setting | What it is |
|---|---|
| Server upload base URL | Where your APK server accepts uploads, e.g. `http://localhost:3001` |
| Server install base URL | Where the install page is served, e.g. `http://localhost:3001` |
| Server download base URL | Where direct APK download is served (often a different port), e.g. `http://localhost:8082` |
| Server builds page URL | Optional dashboard link surfaced in the Chat card |
| Default Gradle task | The task the dialog pre-selects, e.g. `:app:assembleDebug` |
| Additional Gradle tasks | One per line — surfaced in the variant dropdown when sync data is unavailable |
| App name | Used in the auto-generated APK filename (`<appName>-<branch>-<sha>.apk`) |
| Uploader | Who's sharing this build (defaults to your OS username) |
| Environment label | Tagged in upload metadata (e.g. `QA`, `Staging`) |
| JIRA base URL | Makes ticket IDs clickable in the Chat card |
| Base branch for changelog | Blank = auto-detect (`origin/HEAD` → develop/main/master). Set e.g. `develop` to pin it |
| Max commits prefilled | How many commit subjects fill the Changes field before an "…and N more" line (default 10) |
| Pre-fill Changes from recent commits | Toggle the changelog auto-fill on/off |
| Chat webhook URL | Stored in PasswordSafe; must start with `https://chat.googleapis.com/v1/spaces/` |

## Usage

1. Click the **upload icon** in the main toolbar (or **Tools → Share APK**).
2. Pick a branch and build variant. The **Changes** field auto-fills with that branch's commits (edit freely); the **Message** note is optional.
3. JIRA ticket auto-fills from the branch name; edit if needed.
4. Click **OK**. Watch the progress in the IDE status bar and the "APK Share Build" tool window.
5. When done, a notification balloon shows the install URL and a Chat card lands in your configured space.

## Server contract

Your APK server only needs to accept **one HTTP endpoint**. There is no proprietary protocol.

**Endpoint:** `POST {serverUploadBase}/api/builds/upload?platform=android`

**Headers:**

- `Content-Type: application/octet-stream`
- `X-Build-Meta: <compact JSON>` containing:

```json
{
  "branch": "feature/PROJ-99-foo",
  "env": "QA",
  "uploader": "you",
  "notes": "fix login crash (commit a1b2c3d)",
  "filename": "app-feature_PROJ-99-foo-a1b2c3d.apk",
  "jiraTicket": "PROJ-99"
}
```

**Body:** raw APK bytes, streamed in 64 KiB chunks.

**Success response (HTTP 200):**

```json
{
  "ok": true,
  "build": { "id": "abc12345_def67890" },
  "installUrl": "http://localhost:3001/install/abc12345_def67890",
  "downloadUrl": "http://localhost:8082/api/builds/download/abc12345_def67890"
}
```

If your server omits `installUrl` / `downloadUrl`, the plugin falls back to constructing them from the configured base URLs + the returned `build.id`.

**Note on Chat clickability:** Google Chat rewrites every card-mediated click `http://` → `https://`. If your server is HTTP-only, the only reliably clickable affordance is the top-level `text` of the message — and that's what the plugin uses. See [`docs/CHAT_PAYLOAD_NOTES.md`](docs/CHAT_PAYLOAD_NOTES.md) for the full breakdown.

## Development

```bash
./gradlew runIde          # launch a sandbox AS with the plugin loaded
./gradlew test            # JUnit 5 unit tests for payload + JIRA extractor + branch sanitizer
./gradlew buildPlugin     # produce the installable ZIP
```

By default the build wires against your locally-installed Android Studio at `~/Downloads/android-studio` (skipping the ~1 GB platform-SDK download). Override:

```bash
./gradlew -PandroidStudio.path=/Applications/Android\ Studio.app/Contents buildPlugin
```

Architecture notes live in inline KDoc on each service. The non-obvious bits:

- `WorktreeManager` shells out to `git` directly via `ProcessBuilder` — Git4Idea has no public `worktree add` API.
- `GradleBuildService` uses the Gradle Tooling API via the bundled `com.intellij.gradle` dep — no external Gradle process spawned.
- `ApkServerUploadService` streams the APK with a custom `ProgressRequestBody` (64 KiB chunks) so the IDE indicator reflects bytes-uploaded vs total.
- The Chat card's `cardsV2` payload is hand-rolled as a `Map<String, Any>` and serialized inline — keeps deps minimal.
- `JiraIdExtractor` returns the **last** case-insensitive match and uppercases it, so `feature/<subtask>-<parent>-<slug>` naming surfaces the parent ticket regardless of casing.

## Contributing

Issues and PRs welcome. If you're adding a new feature:

1. Pick a small, focused scope.
2. Add a unit test where the logic is non-trivial — see existing tests for `ChatNotifyService`, `JiraIdExtractor`, `BranchSanitizer`.
3. Update the relevant section in this README if user-facing.

CI is not yet wired — running `./gradlew test buildPlugin` locally is the current bar.

## License

MIT — see [LICENSE](LICENSE).

---

Built by [Atish Agrawal](https://github.com/atishagrawal). PRs welcome.
