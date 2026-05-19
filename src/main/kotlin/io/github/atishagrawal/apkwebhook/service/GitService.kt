package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.BranchInfo
import io.github.atishagrawal.apkwebhook.model.WorktreeHandle
import com.intellij.openapi.project.Project
import git4idea.config.GitVcsSettings
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Thin wrapper around Git4Idea for branch enumeration and metadata.
 * Used by the Share APK dialog to populate the branch combo.
 */
class GitService(private val project: Project) {

    /**
     * Enumerate local + remote branches, dedupe by display name, surface recent first.
     * Remote refs are normalised to "origin/<branch>" since builds always target origin.
     */
    fun listBranches(): List<BranchInfo> {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: return emptyList()

        val recentSet: Set<String> = try {
            // recentBranchesByRepository keys by repo root URL; values are recent branch
            // names. Defensive try/catch — the field surface has moved between platform
            // versions, so absence is non-fatal.
            val settings = GitVcsSettings.getInstance(project)
            settings.recentBranchesByRepository.values.toSet()
        } catch (t: Throwable) {
            emptySet()
        }

        val seen = linkedSetOf<String>()
        val out = mutableListOf<BranchInfo>()

        // Recent first — order matters so the most-likely pick floats to the top.
        for (recent in recentSet) {
            if (recent.isBlank()) continue
            if (!seen.add(recent)) continue
            out.add(BranchInfo(displayName = recent, remoteRef = "origin/$recent", isRecent = true))
        }

        // Then remote branches (the canonical target since we always build from origin).
        for (remote in repo.branches.remoteBranches) {
            val nameForRemote = remote.nameForRemoteOperations
            if (nameForRemote.isBlank()) continue
            if (!seen.add(nameForRemote)) continue
            out.add(BranchInfo(displayName = nameForRemote, remoteRef = "origin/$nameForRemote", isRecent = false))
        }

        // Finally local branches that have no matching remote.
        for (local in repo.branches.localBranches) {
            val name = local.name
            if (name.isBlank()) continue
            if (!seen.add(name)) continue
            out.add(BranchInfo(displayName = name, remoteRef = "origin/$name", isRecent = false))
        }

        return out
    }

    /** "origin/<current-branch>" or null if HEAD is detached / repo unavailable. */
    fun currentBranchRemoteRef(): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val current = repo.currentBranch?.name ?: return null
        return "origin/$current"
    }

    /** Bare local branch name (e.g. "release/1") or null if HEAD is detached. */
    fun currentBranchName(): String? {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        return repo.currentBranch?.name
    }

    /**
     * Build-in-place handle: points at the open IDE project root, with HEAD short SHA.
     * Used when the requested branch == currently checked-out branch, so we skip the
     * cached-worktree fetch/reset cycle and let the user's working tree (incl. uncommitted)
     * be the build source.
     */
    fun inPlaceHandle(): WorktreeHandle {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?: throw ApkWebhookException.GitFailed("No Git repository found in project")
        val sha = repo.currentRevision?.take(7)
            ?: throw ApkWebhookException.GitFailed("Could not resolve HEAD short SHA")
        return WorktreeHandle(path = projectBaseDir(), shortSha = sha)
    }

    /** Absolute path to the project's base dir. Throws if basePath is null. */
    fun projectBaseDir(): Path {
        val base = project.basePath
            ?: throw ApkWebhookException.GitFailed("Project has no basePath — cannot locate Git repo.")
        return Paths.get(base)
    }
}
