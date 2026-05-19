package io.github.atishagrawal.apkwebhook.util

object BranchSanitizer {
    private val UNSAFE = Regex("[/ ]")

    fun sanitize(branch: String): String = branch.replace(UNSAFE, "_")
}
