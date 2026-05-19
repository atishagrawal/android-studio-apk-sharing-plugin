package io.github.atishagrawal.apkwebhook.service

/**
 * Typed exception hierarchy for the share-APK pipeline. Each phase
 * surfaces its failure through one of the subclasses so the UI layer
 * can show a phase-specific error balloon.
 */
sealed class ApkWebhookException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** Anything wrong while syncing the cached worktree (fetch / reset / clean / rev-parse). */
    class GitFailed(message: String, cause: Throwable? = null) : ApkWebhookException(message, cause)

    /** Gradle build returned non-zero or threw a BuildException. */
    class BuildFailed(message: String, cause: Throwable? = null) : ApkWebhookException(message, cause)

    /** APK upload to the server failed (non-200, network error, malformed response). */
    class UploadFailed(message: String, cause: Throwable? = null) : ApkWebhookException(message, cause)

    /** Google Chat webhook POST failed. */
    class NotifyFailed(message: String, cause: Throwable? = null) : ApkWebhookException(message, cause)

    /** A required setting (e.g. webhook URL) is missing. */
    class SettingsMissing(message: String, cause: Throwable? = null) : ApkWebhookException(message, cause)
}
