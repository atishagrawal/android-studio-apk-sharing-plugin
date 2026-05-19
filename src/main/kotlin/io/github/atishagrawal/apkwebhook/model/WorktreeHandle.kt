package io.github.atishagrawal.apkwebhook.model

import java.nio.file.Path

data class WorktreeHandle(
    val path: Path,
    val shortSha: String,
)
