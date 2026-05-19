package io.github.atishagrawal.apkwebhook.model

data class UploadResult(
    val buildId: String,
    val installUrl: String,
    val downloadUrl: String,
)
