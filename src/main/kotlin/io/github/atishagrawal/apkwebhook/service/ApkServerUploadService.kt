package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.BuildMeta
import io.github.atishagrawal.apkwebhook.model.UploadResult
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Uploads an APK to the configured APK server via OkHttp and parses the JSON response.
 *
 * Endpoint: `POST {serverUploadBase}/api/builds/upload?platform=android`
 * Headers : `Content-Type: application/octet-stream`, `X-Build-Meta: <compact JSON of BuildMeta>`
 * Body    : raw APK bytes (streamed via a [ProgressRequestBody] so the IDE indicator
 *           reflects bytes-uploaded vs total).
 *
 * Response shape (HTTP 200):
 *   `{"ok": true, "build": {"id": "...", ...}, "installUrl": "...", "downloadUrl": "..."}`
 *
 * Install/download URLs fall back to `{serverInstallBase}/install/{id}` and
 * `{serverDownloadBase}/api/builds/download/{id}` if the server omits them.
 */
class ApkServerUploadService(private val settings: ApkWebhookSettings.State) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Big APKs over slow LAN — keep call/read generous.
            .callTimeout(15, TimeUnit.MINUTES)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .build()
    }

    fun upload(apk: Path, meta: BuildMeta, indicator: ProgressIndicator): UploadResult {
        if (!Files.isRegularFile(apk)) {
            throw ApkWebhookException.UploadFailed("APK not found at $apk")
        }
        val totalBytes = Files.size(apk)
        val metaJson = json.encodeToString(BuildMeta.serializer(), meta)

        val octet = "application/octet-stream".toMediaType()
        val body = ProgressRequestBody(apk, octet, totalBytes) { written, total ->
            // Progress callback fires on the OkHttp dispatcher thread — bounce to EDT
            // to keep the IntelliJ progress indicator happy.
            val fraction = if (total > 0) written.toDouble() / total.toDouble() else 0.0
            ApplicationManager.getApplication().invokeLater {
                if (!indicator.isCanceled) {
                    indicator.fraction = fraction.coerceIn(0.0, 1.0)
                    indicator.text2 =
                        "Uploading APK — ${written / 1024L / 1024L} / ${total / 1024L / 1024L} MB"
                }
            }
        }

        val url = "${settings.serverUploadBase.trimEnd('/')}/api/builds/upload?platform=android"
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("X-Build-Meta", metaJson)
            .header("Content-Type", "application/octet-stream")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApkWebhookException.UploadFailed("Upload to APK server failed: ${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val snippet = try {
                    resp.body?.string().orEmpty().take(2000)
                } catch (_: IOException) {
                    ""
                }
                throw ApkWebhookException.UploadFailed(
                    "Server returned HTTP ${resp.code}: $snippet",
                )
            }
            val raw = try {
                resp.body?.string()
            } catch (e: IOException) {
                throw ApkWebhookException.UploadFailed("Failed to read server response body", e)
            } ?: throw ApkWebhookException.UploadFailed("Server returned empty response body")

            val parsed = try {
                json.decodeFromString(UploadResponse.serializer(), raw)
            } catch (e: Exception) {
                throw ApkWebhookException.UploadFailed(
                    "Server response was not valid JSON: ${e.message}\n$raw",
                    e,
                )
            }
            if (!parsed.ok) {
                throw ApkWebhookException.UploadFailed("Server returned ok=false: $raw")
            }
            val buildId = parsed.build?.id
                ?: throw ApkWebhookException.UploadFailed("Server response missing build.id: $raw")

            val installUrl = parsed.installUrl?.takeIf { it.isNotBlank() }
                ?: "${settings.serverInstallBase.trimEnd('/')}/install/$buildId"
            val downloadUrl = parsed.downloadUrl?.takeIf { it.isNotBlank() }
                ?: "${settings.serverDownloadBase.trimEnd('/')}/api/builds/download/$buildId"

            return UploadResult(buildId = buildId, installUrl = installUrl, downloadUrl = downloadUrl)
        }
    }

    /**
     * Streams a file as the request body and reports progress via [onProgress].
     * Reports the final byte count once the upload completes.
     */
    internal class ProgressRequestBody(
        private val file: Path,
        private val mediaType: MediaType,
        private val totalBytes: Long,
        private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit,
    ) : RequestBody() {

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = totalBytes

        override fun writeTo(sink: BufferedSink) {
            // 64 KiB chunks — balances callback frequency vs syscall overhead.
            val chunk = 64L * 1024L
            var written = 0L
            Files.newInputStream(file).use { input ->
                input.source().use { source ->
                    while (true) {
                        val read = source.read(sink.buffer, chunk)
                        if (read == -1L) break
                        sink.emitCompleteSegments()
                        written += read
                        onProgress(written, totalBytes)
                    }
                }
            }
            onProgress(totalBytes, totalBytes)
        }
    }

    @Serializable
    private data class UploadResponse(
        @SerialName("ok") val ok: Boolean = false,
        @SerialName("build") val build: BuildDto? = null,
        @SerialName("installUrl") val installUrl: String? = null,
        @SerialName("downloadUrl") val downloadUrl: String? = null,
    )

    @Serializable
    private data class BuildDto(
        @SerialName("id") val id: String? = null,
    )
}
