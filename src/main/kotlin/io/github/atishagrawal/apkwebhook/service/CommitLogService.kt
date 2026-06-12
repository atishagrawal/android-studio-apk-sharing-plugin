package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Result of a commit-prefill query. [subjects] is already capped to the requested limit
 * (newest first, merge commits excluded); [extraCount] is how many further commits exist
 * beyond the cap (drives the "…and N more" line) — 0 when nothing was dropped or when the
 * fallback path couldn't compute a meaningful total.
 */
data class CommitSummary(
    val subjects: List<String>,
    val extraCount: Int,
)

/**
 * Computes the commits a branch *adds* over its base, for pre-filling the editable Changes field.
 *
 * All methods shell out to git via git4idea and MUST be called from a background thread.
 * Queries run against the project's own repository root (never the cached build worktree,
 * which is not a VCS root the IDE knows about) — the result is materialized as editable text in
 * the dialog, so the card's Changes section is whatever the user leaves in that field.
 */
class CommitLogService(private val project: Project) {

    private val settings get() = ApkWebhookSettings.getInstance().state

    /**
     * @param branchBareName bare name, e.g. "release/1" (no "origin/" prefix).
     * @param isCurrentBranch true → tip is local HEAD (includes unpushed commits); else origin/<branch>.
     * @param limit max subjects returned; the overflow beyond it becomes [CommitSummary.extraCount].
     */
    fun commitsForShare(branchBareName: String, isCurrentBranch: Boolean, limit: Int): CommitSummary {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return CommitSummary(emptyList(), 0)
        val root = repo.root
        val tip = if (isCurrentBranch) "HEAD" else "origin/$branchBareName"
        val baseRef = detectBaseRef(repo)

        // Preferred: commits unique to the branch (base..tip), merges excluded.
        if (baseRef != null) {
            val ranged = runCatching { loadSubjects(root, "$baseRef..$tip", limit + 1) }.getOrNull()
            if (ranged != null) {
                if (ranged.size <= limit) return CommitSummary(ranged, 0)
                val total = runCatching { countCommits(root, "$baseRef..$tip") }.getOrNull()
                val extra = (total ?: ranged.size) - limit
                return CommitSummary(ranged.take(limit), extra.coerceAtLeast(0))
            }
        }

        // Fallback: last-N on the tip (base unknown / branch not fetched). No reliable overflow
        // count here, so we show exactly `limit` lines without an "…and N more" tail.
        val lastN = runCatching { loadSubjects(root, tip, limit + 1) }.getOrNull()
            ?: runCatching { loadSubjects(root, branchBareName, limit + 1) }.getOrNull()
        if (lastN == null) {
            LOG.info("APK-Webhook: no commits resolved for '$branchBareName' (base=$baseRef, tip=$tip)")
            return CommitSummary(emptyList(), 0)
        }
        return CommitSummary(lastN.take(limit), 0)
    }

    /** Subjects of `git log <revExpr> --no-merges --max-count=<max>`, newest first. */
    private fun loadSubjects(root: VirtualFile, revExpr: String, max: Int): List<String> =
        GitHistoryUtils.history(project, root, revExpr, "--no-merges", "--max-count=$max")
            .map { it.subject }

    /** `git rev-list --count --no-merges <range>` — accurate, merge-excluded total. */
    private fun countCommits(root: VirtualFile, range: String): Int? {
        val handler = GitLineHandler(project, root, GitCommand.REV_LIST).apply {
            addParameters("--count", "--no-merges", range)
            endOptions()
            setSilent(true)
        }
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.output.firstOrNull()?.trim()?.toIntOrNull() else null
    }

    /**
     * Resolves the base ref to diff against: settings override → `origin/HEAD` → develop/main/master.
     * Returns null only when none resolve (caller then falls back to last-N).
     */
    private fun detectBaseRef(repo: GitRepository): String? {
        normalizeBaseRef(settings.baseBranchOverride)?.let { return it }

        val viaHead = runCatching {
            val handler = GitLineHandler(project, repo.root, GitCommand.REV_PARSE).apply {
                addParameters("--abbrev-ref", "origin/HEAD")
                setSilent(true)
            }
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                result.output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "origin/HEAD" }
            } else {
                null
            }
        }.getOrNull()
        if (viaHead != null) return viaHead

        return listOf("origin/develop", "origin/main", "origin/master")
            .firstOrNull { repo.branches.findRemoteBranch(it) != null }
    }

    private companion object {
        private val LOG = logger<CommitLogService>()
    }
}

/**
 * Normalizes a user-supplied base-branch override into a remote ref, or null to auto-detect.
 * Blank → null. A bare name ("develop") → "origin/develop". An explicit "origin/…" → unchanged.
 */
internal fun normalizeBaseRef(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("origin/")) trimmed else "origin/$trimmed"
}
