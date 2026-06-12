# Changelog

All notable changes to the APK Webhook plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — 2026-06-11

### Added

- **Editable "Changes" field** in the Share dialog, pre-filled with the commits unique to the selected branch (merge commits excluded; base auto-detected from `origin/HEAD`, overridable in settings). Curate it before sending; it renders as a dedicated "Changes" section on the Chat card.
- **"Shared by" row** on the Chat card — the git `user.name` of whoever shared the build, falling back to the configured Uploader.
- **Optional multi-line Message** note (no longer required), separate from the changelog.
- New settings: base branch for the changelog, max commits prefilled, and a toggle to disable the prefill.

### Changed

- Card text is now HTML-escaped, so commit subjects or messages containing `<`, `>`, or `&` render correctly instead of corrupting the card.
- Built against Kotlin 2.3.10.

## [0.1.0] — 2026-05-19

### Added

- Initial public release.
- One-click "Share APK" action: pick a branch + build variant, write a message, hit OK.
- Git-worktree-based isolated builds (so the user's working tree is never disturbed); same-branch builds happen in place so uncommitted work is included.
- Build-variant picker auto-discovered from the IDE's Gradle sync data, with fallback to a static configured task list.
- Live Gradle output streamed into a dedicated "APK Share Build" tool window.
- Chunked APK upload with byte-accurate progress reported to the IDE status bar.
- Google Chat `cardsV2` notification with branch / commit / message / JIRA ticket / filename, plus install / download / dashboard URLs in the top-level message text.
- JIRA ticket auto-extraction from branch names (case-insensitive, last-match wins so the `feature/<subtask>-<parent>-<slug>` convention surfaces the parent).
- Settings page under **Tools → APK Webhook** for server URLs, default Gradle task, environment label, and JIRA base URL.
- Chat webhook URL stored in IntelliJ PasswordSafe.
