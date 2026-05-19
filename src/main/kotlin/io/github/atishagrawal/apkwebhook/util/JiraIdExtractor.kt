package io.github.atishagrawal.apkwebhook.util

object JiraIdExtractor {
    // Case-insensitive: a branch may carry the parent ticket lowercased,
    // e.g. `feature/PROJ-100-proj-99-foo`. Output is uppercased.
    private val PATTERN = Regex("[A-Za-z]{2,10}-\\d+")

    // Returns the LAST match: when two ticket IDs appear in a branch name (e.g. the
    // `feature/<subtask>-<parent>-<slug>` naming pattern), prefer the second so the
    // parent ticket surfaces rather than the subtask.
    fun extract(branchName: String): String? =
        PATTERN.findAll(branchName).lastOrNull()?.value?.uppercase()
}
