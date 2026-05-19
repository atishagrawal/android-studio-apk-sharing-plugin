package io.github.atishagrawal.apkwebhook.model

data class ShareRequest(
    val branch: String,
    val gradleTask: String,
    val message: String,
    val jiraTicket: String?,
    val filenameOverride: String?,
)
