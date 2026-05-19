package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.BuildResult
import io.github.atishagrawal.apkwebhook.model.WorktreeHandle
import com.intellij.openapi.progress.ProgressIndicator
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Wraps the Gradle Tooling API (bundled via the IntelliJ `com.intellij.gradle` dep)
 * to run a single Gradle task against the cached worktree.
 *
 * The build output is streamed into the provided [OutputStream] (typically the
 * IDE's Build tool window) AND mirrored to a temp log file so the failure
 * notification can surface a "view full log" link.
 */
class GradleBuildService(private val handle: WorktreeHandle) {

    /**
     * Run [task] (e.g. `:app:assembleDebug`) and return the success / log path.
     *
     * @param outputStream live stream for both stdout and stderr — typically the Build tool window.
     */
    fun run(task: String, indicator: ProgressIndicator, outputStream: OutputStream): BuildResult {
        indicator.text2 = "Running $task…"

        val logFile = newLogFile()
        // Tee output: anything written to the tool window also lands in the log file.
        val tee = TeeOutputStream(outputStream, Files.newOutputStream(logFile))

        return try {
            GradleConnector.newConnector()
                .forProjectDirectory(handle.path.toFile())
                .connect()
                .use { connection ->
                    val build = connection.newBuild()
                        .forTasks(task)
                        .setStandardOutput(tee)
                        .setStandardError(tee)
                    // TODO(verify-api): GradleConnector.addProgressListener wants
                    // org.gradle.tooling.events.ProgressListener (newer Gradle Tooling).
                    // For now we skip listener wiring — the indicator's text2 is set
                    // by the caller and tool-window stream gives live feedback.
                    build.run()
                }
            tee.flush()
            BuildResult(success = true, logFile = logFile)
        } catch (e: BuildException) {
            tee.flush()
            // Don't throw — the caller wants to surface a typed failure with the log path.
            BuildResult(success = false, logFile = logFile)
        } catch (e: RuntimeException) {
            tee.flush()
            // Tooling API can throw GradleConnectionException etc. Treat them as build failures
            // and surface the underlying message via the typed exception so the UI can offer
            // a "copy stderr" affordance.
            throw ApkWebhookException.BuildFailed("Gradle build failed: ${e.message}", e)
        } finally {
            try {
                tee.close()
            } catch (_: IOException) {
                // best-effort
            }
        }
    }

    private fun newLogFile(): Path {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Files.createTempFile("apk-webhook-build-$ts-", ".log")
    }

    /** Forwards writes to both delegates; closes both. */
    private class TeeOutputStream(
        private val a: OutputStream,
        private val b: OutputStream,
    ) : OutputStream() {
        override fun write(byte: Int) {
            a.write(byte); b.write(byte)
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            a.write(buf, off, len); b.write(buf, off, len)
        }

        override fun flush() {
            a.flush(); b.flush()
        }

        override fun close() {
            try {
                a.close()
            } finally {
                b.close()
            }
        }
    }
}
