package io.github.atishagrawal.apkwebhook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the v0.3 formatting helpers. No IDE, no git, no network —
 * these exercise [escapeHtml] (card text is an HTML subset) and [normalizeBaseRef]
 * (settings base-branch override → remote ref).
 */
class ChangelogFormatTest {

    @Test
    fun `escapeHtml leaves plain text untouched`() {
        assertEquals("fix login crash", escapeHtml("fix login crash"))
    }

    @Test
    fun `escapeHtml escapes ampersand first then angle brackets`() {
        assertEquals("a &amp; b", escapeHtml("a & b"))
        assertEquals("&lt;Toolbar&gt;", escapeHtml("<Toolbar>"))
        assertEquals("Fix &lt;X&gt; &amp; Y", escapeHtml("Fix <X> & Y"))
    }

    @Test
    fun `escapeHtml single-pass does not re-escape entities`() {
        assertEquals("&amp;lt;", escapeHtml("&lt;"))
    }

    @Test
    fun `normalizeBaseRef returns null for blank so caller auto-detects`() {
        assertNull(normalizeBaseRef(""))
        assertNull(normalizeBaseRef("   "))
    }

    @Test
    fun `normalizeBaseRef prefixes a bare branch with origin`() {
        assertEquals("origin/develop", normalizeBaseRef("develop"))
        assertEquals("origin/main", normalizeBaseRef("  main  "))
    }

    @Test
    fun `normalizeBaseRef leaves an explicit origin ref unchanged`() {
        assertEquals("origin/release/1", normalizeBaseRef("origin/release/1"))
    }
}
