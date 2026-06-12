package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.ShareRequest
import io.github.atishagrawal.apkwebhook.model.UploadResult
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Posts the Google Chat card describing the freshly-uploaded build.
 *
 * Payload shape:
 *   - top-level `text` → contains the four clickable raw URLs (install, alt-port install,
 *     direct download, builds dashboard). This is the ONLY clickable affordance — Chat
 *     auto-links bare HTTP URLs in `text` and the browser navigates directly.
 *   - `cardsV2` → metadata section (Branch / Source / Shared by / Message / Ticket / File),
 *     plus an optional "Changes" section listing the build's commits.
 *
 * A `buttonList` row is intentionally NOT included. Chat rewrites `http://`→`https://`
 * on every card-mediated click (buttonList, decoratedText.onClick, `<a href>` in
 * textParagraph), and many local APK servers run HTTP only — so buttons would look
 * clickable but always fail the TLS handshake. See `docs/CHAT_PAYLOAD_NOTES.md` for
 * the full rationale.
 */
class ChatNotifyService(
    private val settings: ApkWebhookSettings.State,
    private val webhookUrl: String,
) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun notify(
        req: ShareRequest,
        result: UploadResult,
        shortSha: String,
        uploadFilename: String,
        sharedBy: String,
    ) {
        if (webhookUrl.isBlank()) {
            throw ApkWebhookException.SettingsMissing("Google Chat webhook URL is not configured.")
        }

        val payload = buildPayload(req, result, shortSha, uploadFilename, sharedBy, settings)
        val json = mapToJson(payload)

        val request = Request.Builder()
            .url(webhookUrl)
            .post(json.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApkWebhookException.NotifyFailed("Chat webhook POST failed: ${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val snippet = try {
                    resp.body?.string().orEmpty().take(2000)
                } catch (_: IOException) {
                    ""
                }
                throw ApkWebhookException.NotifyFailed("Chat webhook returned HTTP ${resp.code}: $snippet")
            }
        }
    }
}

/**
 * Builds the Google Chat payload as a heterogeneous [Map]. Kept top-level + internal
 * so unit tests can assert on its structure directly. See class-level KDoc on
 * [ChatNotifyService] for the contract.
 */
internal fun buildPayload(
    req: ShareRequest,
    result: UploadResult,
    shortSha: String,
    uploadFilename: String,
    sharedBy: String,
    settings: ApkWebhookSettings.State,
): Map<String, Any> {
    val install = result.installUrl
    val download = result.downloadUrl
    val builds = settings.serverBuildsPage
    val branch = req.branch

    // If install and download surfaces live on different ports (a split-port deployment),
    // emit an alternate install URL on the download port — useful when the install
    // landing page and the raw-APK endpoint sit behind different reverse proxies.
    // Single-port deployments produce no alt URL.
    val portRegex = Regex("://[^/]*:(\\d+)")
    val installBasePort = portRegex.find(settings.serverInstallBase)?.groupValues?.get(1)
    val downloadBasePort = portRegex.find(settings.serverDownloadBase)?.groupValues?.get(1)
    val altInstall: String? =
        if (installBasePort != null && downloadBasePort != null && installBasePort != downloadBasePort) {
            install.replace(":$installBasePort/", ":$downloadBasePort/").takeIf { it != install }
        } else null

    val textLines = buildList {
        add("*Install App:* $install")
        if (altInstall != null) add("*Alt install URL:* $altInstall")
        add("*Direct APK download:* $download")
        add("*All Builds:* $builds")
    }

    // Section 1 — metadata. Order: Branch, Source, Shared by, [Message?], [Ticket?], File.
    // Message and Ticket rows appear only when non-blank. All free text is HTML-escaped because
    // card text is an HTML subset; newlines become <br>.
    val sectionWidgets = buildList<Map<String, Any>> {
        add(decoratedText("Branch", "${escapeHtml(branch)} @ $shortSha"))
        add(decoratedText("Source", "IDE Plugin (APK Webhook)"))
        add(decoratedText("Shared by", escapeHtml(sharedBy), startIcon = "PERSON"))
        if (req.message.isNotBlank()) {
            add(decoratedText("Message", escapeHtml(req.message).replace("\n", "<br>"), wrapText = true))
        }
        if (!req.jiraTicket.isNullOrBlank()) {
            val href = "${settings.jiraBaseUrl}${req.jiraTicket}"
            add(decoratedText("Ticket", "<a href=\"$href\">${escapeHtml(req.jiraTicket)}</a>"))
        }
        add(decoratedText("File", escapeHtml(uploadFilename)))
    }

    // Section 2 — Changes. Built from the user's edited Changes field: one line per entry,
    // blanks dropped, each escaped, joined with <br>. Omitted entirely when nothing remains.
    val changeLines = req.changelogText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    val sections = buildList<Map<String, Any>> {
        add(mapOf("widgets" to sectionWidgets))
        if (changeLines.isNotEmpty()) {
            add(
                mapOf(
                    "header" to "Changes",
                    "widgets" to listOf(
                        mapOf(
                            "textParagraph" to mapOf(
                                "text" to changeLines.joinToString("<br>") { escapeHtml(it) },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    return mapOf(
        "text" to textLines.joinToString("\n"),
        "cardsV2" to listOf(
            mapOf(
                "cardId" to "apk-notify",
                "card" to mapOf(
                    "header" to mapOf(
                        "title" to "Build Succeeded!",
                        "subtitle" to settings.appName,
                    ),
                    "sections" to sections,
                ),
            ),
        ),
    )
}

private fun decoratedText(
    label: String,
    text: String,
    startIcon: String? = null,
    wrapText: Boolean = false,
): Map<String, Any> {
    val decorated = buildMap {
        put("topLabel", label)
        put("text", text)
        if (startIcon != null) put("startIcon", mapOf("knownIcon" to startIcon))
        if (wrapText) put("wrapText", true)
    }
    return mapOf("decoratedText" to decorated)
}

/**
 * Escapes the HTML-significant characters in card text. Card `text` fields render as an HTML
 * subset, so raw `&`/`<`/`>` in a commit subject, message, or filename would corrupt the card.
 * `&` is escaped first so the `<`/`>` replacements aren't double-escaped.
 */
internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Minimal recursive serializer for the heterogeneous payload — handles
 * `String / Boolean / Number / null / Map<String, Any> / Iterable<Any>`.
 * Kept inline (no Gson / no JSONObject) so the service has zero extra deps
 * beyond OkHttp + kotlinx.serialization, both of which are already shaded.
 */
internal fun mapToJson(value: Any?): String {
    val sb = StringBuilder()
    appendJson(sb, value)
    return sb.toString()
}

private fun appendJson(sb: StringBuilder, value: Any?) {
    when (value) {
        null -> sb.append("null")
        is Boolean -> sb.append(value.toString())
        is Number -> sb.append(value.toString())
        is String -> appendString(sb, value)
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) sb.append(',')
                first = false
                appendString(sb, k.toString())
                sb.append(':')
                appendJson(sb, v)
            }
            sb.append('}')
        }
        is Iterable<*> -> {
            sb.append('[')
            var first = true
            for (item in value) {
                if (!first) sb.append(',')
                first = false
                appendJson(sb, item)
            }
            sb.append(']')
        }
        else -> appendString(sb, value.toString())
    }
}

private fun appendString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u").append("%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append('"')
}
