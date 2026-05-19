package io.github.atishagrawal.apkwebhook.model

import java.nio.file.Path

data class BuildResult(
    val success: Boolean,
    val logFile: Path?,
)
