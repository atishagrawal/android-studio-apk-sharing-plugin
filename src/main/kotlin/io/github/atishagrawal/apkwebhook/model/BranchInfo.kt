package io.github.atishagrawal.apkwebhook.model

data class BranchInfo(
    val displayName: String,
    val remoteRef: String,
    val isRecent: Boolean,
)
