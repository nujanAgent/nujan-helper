// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text another app supplies, as a provider's screens show it (SPEC.md 6.3): characters that show nothing are removed,
 * ordinary text in any script stays, and inside a sentence it is isolated so it cannot reorder the words around it.
 */
class AppTextTest {

    private fun char(codePoint: Int) = String(Character.toChars(codePoint))

    @Test
    fun `a label that overrides the direction of the text after it loses the override`() {
        // With the override the label is drawn as "Mail Helperall.exe"; without it the letters stand as written.
        assertEquals("Mail Helperexe.lla", AppText.clean("Mail Helper${char(0x202E)}exe.lla${char(0x202C)}"))
    }

    @Test
    fun `zero-width characters, variation selectors, tags and controls are removed, line breaks stay`() {
        val text = "Wants${char(0x200B)} files${char(0xFE0F)}${char(0xE0001)}${char(0xE0069)} for\na project\u0007\u0085"
        assertEquals("Wants files for\na project", AppText.clean(text))
    }

    @Test
    fun `ordinary text in any script is kept, in NFC form`() {
        val kept = "Café · Привет · 東京 · مرحبا · ${char(0x1F600)}"
        assertEquals(kept, AppText.clean("Café · Привет · 東京 · مرحبا · ${char(0x1F600)}"))
    }

    @Test
    fun `an isolated label sits between one first-strong isolate and its pop, with no controls of its own`() {
        val isolated = AppText.isolate("Evil${char(0x2069)}${char(0x202E)}App")
        assertTrue(isolated.startsWith("⁨") && isolated.endsWith("⁩"))
        assertEquals("EvilApp", isolated.removePrefix("⁨").removeSuffix("⁩"))
        assertFalse("its own pop is gone, so it cannot close the isolate early", isolated.dropLast(1).contains('⁩'))
    }

    @Test
    fun `a label made only of hidden characters is empty once cleaned`() {
        assertEquals("", AppText.clean("${char(0x200B)}${char(0x202E)}${char(0xE0041)}"))
    }
}
