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
 * Pins down the Google Chat card payload shape. The cardsV2 / top-level-text split
 * is fragile (see docs/CHAT_PAYLOAD_NOTES.md) — these assertions guard the layout
 * against accidental regression. Calls [buildPayload] directly — no network,
 * no service instantiation needed.
 */
class ChatNotifyServicePayloadTest {

    private fun sampleSettings(): ApkWebhookSettings.State = ApkWebhookSettings.State()

    private fun sampleResult(installPort: String = "3001"): UploadResult = UploadResult(
        buildId = "abc12345_def67890",
        installUrl = "http://localhost:$installPort/install/abc12345_def67890",
        downloadUrl = "http://localhost:8082/api/builds/download/abc12345_def67890",
    )

    private fun shareRequest(jira: String? = null): ShareRequest = ShareRequest(
        branch = "release/1",
        gradleTask = ":app:assembleDebug",
        message = "fix login crash",
        jiraTicket = jira,
        filenameOverride = null,
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
    private fun topLabel(widget: Map<String, Any>): String =
        (widget["decoratedText"] as Map<String, Any>)["topLabel"] as String

    @Suppress("UNCHECKED_CAST")
    private fun widgetText(widget: Map<String, Any>): String =
        (widget["decoratedText"] as Map<String, Any>)["text"] as String

    // -- Test 1 -----------------------------------------------------------------------------
    @Test
    fun `null jiraTicket omits Ticket row and yields 4 section-1 widgets`() {
        val payload = buildPayload(
            req = shareRequest(jira = null),
            result = sampleResult(),
            shortSha = "a1b2c3d",
            uploadFilename = "app-release_1-a1b2c3d.apk",
            settings = sampleSettings(),
        )

        val widgets = section1Widgets(payload)
        assertEquals(4, widgets.size, "expected exactly Branch / Source / Message / File (no Ticket)")
        val labels = widgets.map(::topLabel)
        assertEquals(listOf("Branch", "Source", "Message", "File"), labels)
        assertFalse(labels.contains("Ticket"), "Ticket row must be absent when jiraTicket is null")
    }

    // -- Test 2 -----------------------------------------------------------------------------
    @Test
    fun `non-null jiraTicket adds Ticket row with anchor HTML`() {
        val payload = buildPayload(
            req = shareRequest(jira = "PROJ-1234"),
            result = sampleResult(),
            shortSha = "a1b2c3d",
            uploadFilename = "app-release_1-a1b2c3d.apk",
            settings = sampleSettings(),
        )

        val widgets = section1Widgets(payload)
        assertEquals(5, widgets.size, "expected Branch / Source / Message / Ticket / File")
        val ticket = widgets.firstOrNull { topLabel(it) == "Ticket" }
        assertNotNull(ticket, "Ticket row must be present when jiraTicket is non-null")
        assertEquals(
            "<a href=\"https://your-org.atlassian.net/browse/PROJ-1234\">PROJ-1234</a>",
            widgetText(ticket!!),
        )
    }

    // -- Test 3 -----------------------------------------------------------------------------
    @Test
    fun `top-level text has 4 lines when install URL contains 3001`() {
        val payload = buildPayload(
            req = shareRequest(),
            result = sampleResult(installPort = "3001"),
            shortSha = "a1b2c3d",
            uploadFilename = "app-release_1-a1b2c3d.apk",
            settings = sampleSettings(),
        )

        val text = payload["text"] as String
        val lines = text.split("\n")
        assertEquals(4, lines.size, "expected install / alt-install / download / all-builds lines, got:\n$text")
        assertTrue(lines[0].startsWith("*Install App:*"), "line 0 should be install: ${lines[0]}")
        assertTrue(lines[1].startsWith("*Alt install URL:*"), "line 1 should be alt-install: ${lines[1]}")
        assertTrue(lines[2].startsWith("*Direct APK download:*"), "line 2 should be download: ${lines[2]}")
        assertTrue(lines[3].startsWith("*All Builds:*"), "line 3 should be all-builds: ${lines[3]}")
        // Sanity-check the port swap.
        assertTrue(":8082/" in lines[1], "alt install URL should swap :3001/ → :8082/: ${lines[1]}")
    }

    // -- Test 4 -----------------------------------------------------------------------------
    @Test
    fun `cardId is exactly apk-notify`() {
        val payload = buildPayload(
            req = shareRequest(),
            result = sampleResult(),
            shortSha = "a1b2c3d",
            uploadFilename = "app-release_1-a1b2c3d.apk",
            settings = sampleSettings(),
        )

        @Suppress("UNCHECKED_CAST")
        val cardsV2 = payload["cardsV2"] as List<Map<String, Any>>
        assertEquals(1, cardsV2.size)
        assertEquals("apk-notify", cardsV2[0]["cardId"])
    }

    // -- Test 5 -----------------------------------------------------------------------------
    @Test
    fun `card has exactly one section and no buttonList`() {
        // Locks in the current layout: the buttonList row was removed because
        // Chat's https-rewrite policy made it permanently broken against HTTP-only servers.
        // Top-level text URLs are the only clickable affordance. See docs/CHAT_PAYLOAD_NOTES.md.
        val payload = buildPayload(
            req = shareRequest(),
            result = sampleResult(),
            shortSha = "a1b2c3d",
            uploadFilename = "app-release_1-a1b2c3d.apk",
            settings = sampleSettings(),
        )

        val sections = sections(payload)
        assertEquals(1, sections.size, "expected a single metadata section, got: $sections")
        val widgetsJson = sections[0].toString()
        assertFalse(widgetsJson.contains("buttonList"), "buttonList must be absent: $widgetsJson")
    }
}
