package io.github.atishagrawal.apkwebhook.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BranchSanitizerTest {

    @Test
    fun `replaces slash with underscore`() {
        assertEquals("release_1", BranchSanitizer.sanitize("release/1"))
    }

    @Test
    fun `replaces slashes and spaces with underscores`() {
        assertEquals("feature_abc_xyz", BranchSanitizer.sanitize("feature/abc xyz"))
    }

    @Test
    fun `leaves simple name unchanged`() {
        assertEquals("simple", BranchSanitizer.sanitize("simple"))
    }

    @Test
    fun `replaces multiple slashes`() {
        assertEquals("a_b_c", BranchSanitizer.sanitize("a/b/c"))
    }
}
