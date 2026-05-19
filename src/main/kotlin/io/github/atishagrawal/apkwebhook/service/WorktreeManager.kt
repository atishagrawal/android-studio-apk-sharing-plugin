package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.WorktreeHandle
import io.github.atishagrawal.apkwebhook.util.BranchSanitizer
import com.intellij.openapi.progress.ProgressIndicator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Encapsulates the cached-worktree lifecycle for the share-APK pipeline.
 *
 * - Cache root defaults to `~/.cache/apk-webhook/worktrees`.
 * - Per-branch dir: `<cacheRoot>/<appName>-<sanitized-branch>/`.
 * - First use:   `git -C <projectBaseDir> fetch origin <branch>` then `git -C <projectBaseDir> worktree add --force --detach <cacheDir> origin/<branch>`.
 * - Reuse:       `git -C <cacheDir> fetch origin <branch>` → `reset --hard origin/<branch>` → `clean -fdx`.
 * - Short SHA captured via `git rev-parse --short HEAD`.
 *
 * Shells out to the system `git` binary — Git4Idea has no public worktree-add API.
 */
class WorktreeManager(
    private val projectBaseDir: Path,
    private val cacheRoot: Path = Paths.get(System.getProperty("user.home"), ".cache", "apk-webhook", "worktrees"),
    private val appName: String,
) {

    /**
     * Sync the cached worktree for [branch] to `origin/<branch>` and return a [WorktreeHandle].
     * Phases reported via [indicator]:
     *   - "Creating worktree…" (first run) or "Reusing worktree…"
     *   - "Fetching origin/<branch>…"
     *   - "Resetting to origin/<branch>…"
     *   - "Cleaning worktree…"
     *   - "Resolving HEAD…"
     */
    fun syncToOrigin(branch: String, indicator: ProgressIndicator): WorktreeHandle {
        val sanitized = BranchSanitizer.sanitize(branch)
        val worktreeDir = cacheRoot.resolve("$appName-$sanitized")

        try {
            Files.createDirectories(cacheRoot)
        } catch (e: IOException) {
            throw ApkWebhookException.GitFailed("Failed to create worktree cache root: $cacheRoot", e)
        }

        val hasGit = Files.exists(worktreeDir.resolve(".git"))

        if (!hasGit) {
            indicator.text2 = "Fetching origin/$branch…"
            // Fetch from the source repo first so the ref is known before adding the worktree.
            try {
                runGit(projectBaseDir, "fetch", "origin", branch)
            } catch (e: ApkWebhookException.GitFailed) {
                throw ApkWebhookException.GitFailed(
                    "origin/$branch not fetchable — does the branch exist on origin?",
                    e,
                )
            }
            indicator.text2 = "Creating worktree at $worktreeDir…"
            runGit(
                projectBaseDir,
                "worktree", "add", "--force", "--detach",
                worktreeDir.toString(), "origin/$branch",
            )
        } else {
            indicator.text2 = "Reusing worktree at $worktreeDir…"
        }

        indicator.text2 = "Fetching origin/$branch…"
        runGit(worktreeDir, "fetch", "origin", branch)

        indicator.text2 = "Resetting to origin/$branch…"
        runGit(worktreeDir, "reset", "--hard", "origin/$branch")

        indicator.text2 = "Cleaning worktree…"
        runGit(worktreeDir, "clean", "-fdx")

        indicator.text2 = "Resolving HEAD…"
        val shortSha = runGit(worktreeDir, "rev-parse", "--short", "HEAD").trim()

        if (shortSha.isBlank()) {
            throw ApkWebhookException.GitFailed("git rev-parse returned empty short SHA in $worktreeDir")
        }

        return WorktreeHandle(path = worktreeDir, shortSha = shortSha)
    }

    /**
     * Run `git <args...>` with cwd = [cwd]. Returns stdout (UTF-8) on success;
     * throws [ApkWebhookException.GitFailed] on non-zero exit. Combined stderr
     * is included in the exception message for diagnosis.
     */
    internal fun runGit(cwd: Path, vararg args: String): String {
        val cmd = mutableListOf("git").apply { addAll(args) }
        val pb = ProcessBuilder(cmd)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
        val process = try {
            pb.start()
        } catch (e: IOException) {
            throw ApkWebhookException.GitFailed("Failed to start `git ${args.joinToString(" ")}`", e)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            throw ApkWebhookException.GitFailed("`git ${args.joinToString(" ")}` timed out after 10 minutes")
        }
        val exit = process.exitValue()
        if (exit != 0) {
            throw ApkWebhookException.GitFailed(
                "`git ${args.joinToString(" ")}` failed (exit $exit) in $cwd\n$output",
            )
        }
        return output
    }
}
