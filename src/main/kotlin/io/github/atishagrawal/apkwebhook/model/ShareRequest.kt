package io.github.atishagrawal.apkwebhook.model

/**
 * User input collected by `ShareApkDialog`.
 *
 * - [message] is an optional free-text note (may be blank) — it is not the commit list.
 * - [changelogText] is the editable Changes field, pre-filled from the branch's commits and
 *   curated by the user. It is rendered verbatim (split into lines) as the card's Changes section.
 */
data class ShareRequest(
    val branch: String,
    val gradleTask: String,
    val message: String,
    val jiraTicket: String?,
    val filenameOverride: String?,
    val changelogText: String,
)
