package io.github.atishagrawal.apkwebhook.service

import io.github.atishagrawal.apkwebhook.model.ShareRequest
import io.github.atishagrawal.apkwebhook.model.UploadResult
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins down the Google Chat card payload shape (v0.3). The cardsV2 / top-level-text split
 * is fragile (see docs/CHAT_PAYLOAD_NOTES.md) — these assertions guard the layout against
 * accidental regression. Calls [buildPayload] directly — no network, no service instantiation.
 *
 * v0.3 contract:
 *  - Section 1 widgets, in order: Branch, Source, Shared by, [Message?], [Ticket?], File.
 *    Message and Ticket rows appear only when non-blank.
 *  - A second "Changes" section appears iff `changelogText` has ≥1 non-blank line.
 *  - Card text (Message, Changes) is HTML-escaped; newlines become <br>.
 *  - No `buttonList` anywhere. Top-level `text` still carries the clickable URLs.
 */
class ChatNotifyServicePayloadTest {

    private val sharedBy = "Jane Dev"

    private fun sampleSettings(): ApkWebhookSettings.State = ApkWebhookSettings.State()

    private fun sampleResult(installPort: String = "3001"): UploadResult = UploadResult(
        buildId = "abc12345_def67890",
        installUrl = "http://localhost:$installPort/install/abc12345_def67890",
        downloadUrl = "http://localhost:8082/api/builds/download/abc12345_def67890",
    )

    private fun shareRequest(
        message: String = "fix login crash",
        jira: String? = null,
        changelog: String = "",
    ): ShareRequest = ShareRequest(
        branch = "release/1",
        gradleTask = ":app:assembleDebug",
        message = message,
        jiraTicket = jira,
        filenameOverride = null,
        changelogText = changelog,
    )

    private fun payloadOf(
        req: ShareRequest = shareRequest(),
        result: UploadResult = sampleResult(),
    ): Map<String, Any> = buildPayload(
        req = req,
        result = result,
        shortSha = "a1b2c3d",
        uploadFilename = "app-release_1-a1b2c3d.apk",
        sharedBy = sharedBy,
        settings = sampleSettings(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun sections(payload: Map<String, Any>): List<Map<String, Any>> {
        val cardsV2 = payload["cardsV2"] as List<Map<String, Any>>
        val card = cardsV2[0]["card"] as Map<String, Any>
        return card["sections"] as List<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun section1Widgets(payload: Map<String, Any>): List<Map<String, Any>> =
        sections(payload)[0]["widgets"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun decorated(widget: Map<String, Any>): Map<String, Any> =
        widget["decoratedText"] as Map<String, Any>

    private fun topLabel(widget: Map<String, Any>): String = decorated(widget)["topLabel"] as String
    private fun widgetText(widget: Map<String, Any>): String = decorated(widget)["text"] as String

    @Suppress("UNCHECKED_CAST")
    private fun changesText(payload: Map<String, Any>): String {
        val changes = sections(payload).first { it["header"] == "Changes" }
        val widgets = changes["widgets"] as List<Map<String, Any>>
        val tp = widgets[0]["textParagraph"] as Map<String, Any>
        return tp["text"] as String
    }

    @Test
    fun `blank message and null ticket yield Branch Source SharedBy File`() {
        val widgets = section1Widgets(payloadOf(shareRequest(message = "", jira = null)))
        assertEquals(listOf("Branch", "Source", "Shared by", "File"), widgets.map(::topLabel))
    }

    @Test
    fun `message and ticket add their rows in order`() {
        val widgets = section1Widgets(payloadOf(shareRequest(message = "fix login crash", jira = "PROJ-1234")))
        assertEquals(
            listOf("Branch", "Source", "Shared by", "Message", "Ticket", "File"),
            widgets.map(::topLabel),
        )
    }

    @Test
    fun `Shared by row carries the resolved name and a person icon`() {
        val widgets = section1Widgets(payloadOf())
        val row = widgets.first { topLabel(it) == "Shared by" }
        assertEquals(sharedBy, widgetText(row))

        @Suppress("UNCHECKED_CAST")
        val startIcon = decorated(row)["startIcon"] as Map<String, Any>
        assertEquals("PERSON", startIcon["knownIcon"])
    }

    @Test
    fun `Message row escapes HTML wraps text and converts newlines`() {
        val req = shareRequest(message = "Fix <Toolbar> & nav\nsecond line")
        val row = section1Widgets(payloadOf(req)).first { topLabel(it) == "Message" }
        assertEquals("Fix &lt;Toolbar&gt; &amp; nav<br>second line", widgetText(row))
        assertEquals(true, decorated(row)["wrapText"])
    }

    @Test
    fun `Ticket row keeps an anchor with the ticket id`() {
        val req = shareRequest(jira = "PROJ-1234")
        val row = section1Widgets(payloadOf(req)).first { topLabel(it) == "Ticket" }
        assertEquals(
            "<a href=\"https://your-org.atlassian.net/browse/PROJ-1234\">PROJ-1234</a>",
            widgetText(row),
        )
    }

    @Test
    fun `empty changelog yields a single section`() {
        assertEquals(1, sections(payloadOf(shareRequest(changelog = ""))).size)
    }

    @Test
    fun `whitespace-only changelog yields a single section`() {
        assertEquals(1, sections(payloadOf(shareRequest(changelog = "   \n  \n"))).size)
    }

    @Test
    fun `non-empty changelog adds a Changes section with blank lines dropped`() {
        val payload = payloadOf(shareRequest(changelog = "Fix A\n  \nFix B\n"))
        assertEquals(2, sections(payload).size)
        assertEquals("Changes", sections(payload)[1]["header"])
        assertEquals("Fix A<br>Fix B", changesText(payload))
    }

    @Test
    fun `Changes section escapes HTML in each line`() {
        val payload = payloadOf(shareRequest(changelog = "Fix <X> & Y\nBump dep"))
        assertEquals("Fix &lt;X&gt; &amp; Y<br>Bump dep", changesText(payload))
    }

    @Test
    fun `top-level text has 4 lines when install and download ports differ`() {
        val text = payloadOf(result = sampleResult(installPort = "3001"))["text"] as String
        val lines = text.split("\n")
        assertEquals(4, lines.size, "expected install / alt-install / download / all-builds, got:\n$text")
        assertTrue(lines[0].startsWith("*Install App:*"))
        assertTrue(lines[1].startsWith("*Alt install URL:*"))
        assertTrue(lines[2].startsWith("*Direct APK download:*"))
        assertTrue(lines[3].startsWith("*All Builds:*"))
        assertTrue(":8082/" in lines[1], "alt install URL should swap :3001/ → :8082/: ${lines[1]}")
    }

    @Test
    fun `cardId is exactly apk-notify`() {
        @Suppress("UNCHECKED_CAST")
        val cardsV2 = payloadOf()["cardsV2"] as List<Map<String, Any>>
        assertEquals(1, cardsV2.size)
        assertEquals("apk-notify", cardsV2[0]["cardId"])
    }

    @Test
    fun `no section ever contains a buttonList`() {
        val payload = payloadOf(shareRequest(message = "m", jira = "PROJ-1", changelog = "Fix A"))
        val asString = sections(payload).toString()
        assertFalse(asString.contains("buttonList"), "buttonList must be absent: $asString")
    }

    @Test
    fun `header title and subtitle are set`() {
        @Suppress("UNCHECKED_CAST")
        val card = (payloadOf()["cardsV2"] as List<Map<String, Any>>)[0]["card"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val header = card["header"] as Map<String, Any>
        assertNotNull(header["title"])
        assertEquals(ApkWebhookSettings.State().appName, header["subtitle"])
    }
}
