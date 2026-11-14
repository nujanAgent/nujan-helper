package com.nujan.helper.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathsTest {

    private val root = "/workspaces/proj1"

    @Test
    fun `simple relative path resolves under root`() {
        assertEquals("$root/src/main.kt", WorkspacePaths.resolve(root, "src/main.kt"))
    }

    @Test
    fun `empty and dot path resolve to root itself`() {
        assertEquals(root, WorkspacePaths.resolve(root, ""))
        assertEquals(root, WorkspacePaths.resolve(root, "."))
        assertEquals(root, WorkspacePaths.resolve(root, "./"))
    }

    @Test
    fun `redundant separators and dot segments collapse`() {
        assertEquals("$root/a/b", WorkspacePaths.resolve(root, "a//b"))
        assertEquals("$root/a/b", WorkspacePaths.resolve(root, "a/./b"))
        assertEquals("$root/a/b", WorkspacePaths.resolve(root, "./a/b/"))
    }

    @Test
    fun `interior dotdot that stays inside root is allowed`() {
        assertEquals("$root/a/c", WorkspacePaths.resolve(root, "a/b/../c"))
    }

    @Test
    fun `dotdot escaping root is rejected`() {
        assertThrows(WorkspacePaths.PathViolation::class.java) {
            WorkspacePaths.resolve(root, "../secret")
        }
    }

    @Test
    fun `dotdot that nets outside root is rejected even when it dips inside first`() {
        // a/../.. -> pops "a", then attempts to pop past root -> violation.
        assertThrows(WorkspacePaths.PathViolation::class.java) {
            WorkspacePaths.resolve(root, "a/../../etc/passwd")
        }
    }

    @Test
    fun `absolute path is rejected`() {
        assertThrows(WorkspacePaths.PathViolation::class.java) {
            WorkspacePaths.resolve(root, "/etc/passwd")
        }
    }

    @Test
    fun `backslash is rejected as a traversal-obfuscation vector`() {
        assertThrows(WorkspacePaths.PathViolation::class.java) {
            WorkspacePaths.resolve(root, "a\\..\\..\\etc")
        }
    }

    @Test
    fun `nul byte is rejected`() {
        assertThrows(WorkspacePaths.PathViolation::class.java) {
            WorkspacePaths.resolve(root, "a${Char(0)}b")
        }
    }

    @Test
    fun `non-absolute root is rejected`() {
        assertThrows(WorkspacePaths.PathViolation::class.java) {
            WorkspacePaths.resolve("workspaces/proj1", "a")
        }
    }

    @Test
    fun `trailing slash on root is normalized`() {
        assertEquals("$root/a", WorkspacePaths.resolve("$root/", "a"))
    }

    @Test
    fun `isWithin mirrors resolve without throwing`() {
        assertTrue(WorkspacePaths.isWithin(root, "a/b"))
        assertFalse(WorkspacePaths.isWithin(root, "../b"))
        assertFalse(WorkspacePaths.isWithin(root, "a\\b"))
    }
}
