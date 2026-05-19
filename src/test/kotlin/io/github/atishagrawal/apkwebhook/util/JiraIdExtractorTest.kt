package io.github.atishagrawal.apkwebhook.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JiraIdExtractorTest {

    @Test
    fun `extracts ticket id from feature branch`() {
        assertEquals("PROJ-1234", JiraIdExtractor.extract("feature/PROJ-1234-login-fix"))
    }

    @Test
    fun `returns null when no ticket id present`() {
        assertNull(JiraIdExtractor.extract("release/1"))
    }

    @Test
    fun `extracts short ticket id from bugfix branch`() {
        assertEquals("ABC-99", JiraIdExtractor.extract("bugfix/ABC-99-x"))
    }

    @Test
    fun `returns last match when multiple ticket ids present`() {
        // For the feature/<subtask>-<parent>-<slug> branch naming pattern, the parent
        // ticket comes second; prefer the last match so the parent surfaces.
        assertEquals("PROJ-2", JiraIdExtractor.extract("feature/PROJ-1-PROJ-2"))
    }

    @Test
    fun `prefers parent ticket when subtask is upper and parent is lowercased`() {
        // Realistic feature/<subtask>-<parent>-<slug> naming where the parent is
        // sometimes lowercased: PROJ-100 is the subtask; PROJ-99 is the parent we want.
        assertEquals(
            "PROJ-99",
            JiraIdExtractor.extract("feature/PROJ-100-proj-99-some-slug"),
        )
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(JiraIdExtractor.extract(""))
    }

    @Test
    fun `extracts and uppercases a fully-lowercase ticket id`() {
        // Case-insensitive matching: a lone lowercase ticket id still resolves and
        // gets normalized to canonical UPPERCASE on the way out.
        assertEquals("PROJ-123", JiraIdExtractor.extract("lowercase-proj-123"))
    }
}
