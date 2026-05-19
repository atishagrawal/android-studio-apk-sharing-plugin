package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.WorktreeHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Locates the freshly-built APK under the worktree's `app/build/outputs/apk/` tree:
 * walk the dir, filter `*.apk`, pick newest, reject anything over the plugin's
 * 500 MB safety cap.
 */
class ApkLocator(private val handle: WorktreeHandle) {

    /** Plugin-side cap to short-circuit obviously broken builds before they upload. */
    private val maxBytes: Long = 500L * 1024L * 1024L

    /**
     * Returns the most-recently-modified `.apk` file under `app/build/outputs/apk/`.
     * @throws IllegalStateException if no APK is found or the newest one exceeds 500 MB.
     */
    fun findNewestApk(): Path {
        val searchRoot = handle.path.resolve("app/build/outputs/apk")
        if (!Files.isDirectory(searchRoot)) {
            throw IllegalStateException(
                "No app/build/outputs/apk directory under ${handle.path} — did the Gradle task actually build an APK?",
            )
        }

        val apks = Files.walk(searchRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk") }
                .collect(Collectors.toList())
        }

        if (apks.isEmpty()) {
            throw IllegalStateException("No *.apk found under $searchRoot")
        }

        val newest = apks.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
            ?: throw IllegalStateException("No *.apk found under $searchRoot")

        val size = Files.size(newest)
        if (size > maxBytes) {
            val mb = size / 1024L / 1024L
            throw IllegalStateException("APK is $mb MB — exceeds the plugin's 500 MB safety cap ($newest)")
        }

        return newest
    }
}
