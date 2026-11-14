package com.nujan.helper.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIdTest {
    @Test fun `a clean name passes through lowercased`() =
        assertEquals("myproject", WorkspaceId.fromName("MyProject"))

    @Test fun `spaces and symbols collapse to single dashes`() =
        assertEquals("my-cool-app", WorkspaceId.fromName("My  Cool!! App"))

    @Test fun `leading and trailing separators are trimmed`() =
        assertEquals("app", WorkspaceId.fromName("  ...app...  "))

    @Test fun `path separators cannot survive (no traversal in an id)`() {
        val id = WorkspaceId.fromName("../../etc/passwd")
        assertFalse(id.contains('/'))
        assertFalse(id.contains('.'))
        assertEquals("etc-passwd", id)
    }

    @Test fun `an all-symbol name is rejected, not silently empty`() {
        assertThrows(WorkspaceId.InvalidName::class.java) { WorkspaceId.fromName("!@#\$%") }
    }

    @Test fun `isValid accepts a good id and rejects a dashy or slashed one`() {
        assertTrue(WorkspaceId.isValid("web-app-1"))
        assertFalse(WorkspaceId.isValid("-bad"))
        assertFalse(WorkspaceId.isValid("a/b"))
        assertFalse(WorkspaceId.isValid(""))
    }
}
