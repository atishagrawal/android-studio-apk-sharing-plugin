package io.github.atishagrawal.apkwebhook.action

import io.github.atishagrawal.apkwebhook.model.BuildMeta
import io.github.atishagrawal.apkwebhook.service.ApkLocator
import io.github.atishagrawal.apkwebhook.service.ApkWebhookException
import io.github.atishagrawal.apkwebhook.service.ApkServerUploadService
import io.github.atishagrawal.apkwebhook.service.ChatNotifyService
import io.github.atishagrawal.apkwebhook.service.GitService
import io.github.atishagrawal.apkwebhook.service.GradleBuildService
import io.github.atishagrawal.apkwebhook.service.WorktreeManager
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSecrets
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import io.github.atishagrawal.apkwebhook.ui.ShareApkBuildToolWindow
import io.github.atishagrawal.apkwebhook.ui.ShareApkDialog
import io.github.atishagrawal.apkwebhook.util.BranchSanitizer
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Entry point for the "Share APK" toolbar / Tools-menu action. Opens [ShareApkDialog] and, on
 * confirm, runs the full pipeline (worktree-or-in-place → gradle → upload → chat) in a
 * background task with progress reported to the IDE status bar AND live Gradle output streamed
 * to the "APK Share Build" tool window.
 *
 * Worktree behavior: if the requested branch == the currently checked-out local branch, the
 * pipeline builds against the project root directly (preserving uncommitted work). Otherwise it
 * uses the shared cached worktree at `~/.cache/apk-webhook/worktrees/` so the user's working
 * tree is never touched.
 */
class ShareApkAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = ApkWebhookSettings.getInstance().state
        val secrets = ApkWebhookSecrets.getInstance()
        val webhookUrl = secrets.getWebhookUrl()
        if (webhookUrl.isNullOrBlank()) {
            notify(
                project,
                "APK Webhook not configured",
                "Configure the Chat webhook URL in Settings → Tools → APK Webhook.",
                NotificationType.WARNING,
            )
            return
        }

        val gitService = GitService(project)
        val dialog = ShareApkDialog(project, gitService, settings)
        if (!dialog.showAndGet()) return
        val req = dialog.getShareRequest()

        // Set up the tool window on EDT before launching the background task.
        val console = ShareApkBuildToolWindow.openFreshConsole(
            project,
            "── Share APK: ${req.branch} ────────────────────────────",
        )
        val gradleStream = ShareApkBuildToolWindow.asStream(console)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Sharing APK from ${req.branch}…",
            /* canBeCancelled = */ true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                fun logSystem(msg: String) =
                    console.print(msg + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                fun logError(msg: String) =
                    console.print(msg + "\n", ConsoleViewContentType.ERROR_OUTPUT)

                try {
                    val currentBranch = runCatching { gitService.currentBranchName() }.getOrNull()
                    val sameBranch = currentBranch != null && currentBranch == req.branch
                    val handle = if (sameBranch) {
                        indicator.text = "Building in place (same branch as checkout)…"
                        logSystem("→ Same branch as current checkout ($currentBranch) — building in project root, skipping cached worktree. Uncommitted changes will be included in this build.")
                        gitService.inPlaceHandle()
                    } else {
                        indicator.text = "Syncing worktree…"
                        logSystem("→ Different branch from current checkout (${currentBranch ?: "<detached>"} → ${req.branch}) — syncing cached worktree so your working tree stays untouched.")
                        WorktreeManager(
                            projectBaseDir = gitService.projectBaseDir(),
                            appName = settings.appName,
                        ).syncToOrigin(req.branch, indicator)
                    }
                    val shortSha = handle.shortSha
                    logSystem("→ Build root: ${handle.path}")
                    logSystem("→ HEAD: $shortSha")

                    indicator.text = "Running ${req.gradleTask}…"
                    logSystem("→ Running ./gradlew ${req.gradleTask}")
                    val buildResult = GradleBuildService(handle).run(req.gradleTask, indicator, gradleStream)
                    if (!buildResult.success) {
                        logError("✗ Gradle build failed. Full log: ${buildResult.logFile}")
                        throw ApkWebhookException.BuildFailed("Gradle build failed; see APK Share Build tool window")
                    }
                    logSystem("✓ Build succeeded.")

                    indicator.text = "Locating APK…"
                    val apk = ApkLocator(handle).findNewestApk()
                    logSystem("→ APK: $apk")

                    val sanitized = BranchSanitizer.sanitize(req.branch.removePrefix("origin/"))
                    val uploadFilename = req.filenameOverride?.takeIf { it.isNotBlank() }
                        ?: "${settings.appName}-$sanitized-$shortSha.apk"

                    // Prefer the repo's git user.name; fall back to the configured uploader.
                    // Used for both the card "Shared by" row and the upload metadata.
                    val sharedBy = runCatching { gitService.authorName() }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: settings.uploader

                    val notes = if (req.message.isBlank()) {
                        "(commit $shortSha)"
                    } else {
                        "${req.message} (commit $shortSha)"
                    }
                    val meta = BuildMeta(
                        branch = req.branch,
                        env = settings.envLabel,
                        uploader = sharedBy,
                        notes = notes,
                        filename = uploadFilename,
                        jiraTicket = req.jiraTicket?.takeIf { it.isNotBlank() },
                    )

                    indicator.text = "Uploading to APK server…"
                    logSystem("→ Uploading as $uploadFilename to ${settings.serverUploadBase}…")
                    val result = ApkServerUploadService(settings).upload(apk, meta, indicator)
                    logSystem("✓ Uploaded. Install: ${result.installUrl}")

                    indicator.text = "Posting Chat card…"
                    ChatNotifyService(settings, webhookUrl).notify(req, result, shortSha, uploadFilename, sharedBy)
                    logSystem("✓ Chat card posted.")

                    ApplicationManager.getApplication().invokeLater {
                        notify(
                            project,
                            "APK shared!",
                            "${req.branch} @ $shortSha\n${result.installUrl}",
                            NotificationType.INFORMATION,
                            openUrl = result.installUrl,
                        )
                    }
                } catch (e: Exception) {
                    logError("✗ ${e.message ?: e.javaClass.simpleName}")
                    ApplicationManager.getApplication().invokeLater {
                        notify(
                            project,
                            "Share failed",
                            e.message ?: e.javaClass.simpleName,
                            NotificationType.ERROR,
                        )
                    }
                }
            }
        })
    }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType,
        openUrl: String? = null,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("APK Webhook")
            .createNotification(title, content, type)
        if (openUrl != null) {
            notification.addAction(NotificationAction.createSimple("Open install page") {
                BrowserUtil.browse(openUrl)
            })
        }
        notification.notify(project)
    }
}
