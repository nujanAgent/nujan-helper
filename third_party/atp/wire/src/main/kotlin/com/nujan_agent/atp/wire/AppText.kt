// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import java.text.Normalizer

/**
 * Text another app supplies, made safe to show inside a sentence of one's own (SPEC.md 6.3): an app's label, a host's
 * purpose, a provider's tool descriptions. Characters that show nothing can hide words or reorder the text around them,
 * so [clean] removes control characters other than tab and line breaks, bidirectional controls, zero-width characters,
 * variation selectors, Unicode Tags and interlinear annotation marks, and puts the rest in NFC form. [isolate] also wraps
 * it in a first-strong isolate, so even text written right to left cannot move the words beside it.
 */
object AppText {

    fun clean(text: String): String {
        val normal = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (normal.none { it.isSurrogate() || hidden(it.code) }) return normal
        val kept = StringBuilder(normal.length)
        var i = 0
        while (i < normal.length) {
            val codePoint = normal.codePointAt(i)
            if (!hidden(codePoint)) kept.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        return kept.toString()
    }

    /** [text], cleaned, between U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE. */
    fun isolate(text: String): String = "⁨" + clean(text) + "⁩"

    private fun hidden(codePoint: Int): Boolean =
        (codePoint < 0x20 && codePoint != 0x09 && codePoint != 0x0A && codePoint != 0x0D) ||
            codePoint in 0x7F..0x9F ||
            codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F ||
            codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069 ||
            codePoint in 0x200B..0x200D || codePoint in 0x2060..0x2064 || codePoint == 0xFEFF || codePoint == 0x180E ||
            codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF ||
            codePoint in 0xE0000..0xE007F ||
            codePoint in 0xFFF9..0xFFFB ||
            codePoint in 0xD800..0xDFFF
}
