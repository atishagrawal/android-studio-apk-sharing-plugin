# Changelog

All notable changes to the APK Webhook plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
