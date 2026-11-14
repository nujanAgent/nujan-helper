package com.nujan.helper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Licences page shows the third-party notices without Markdown's marks, and keeps every web address readable. */
class LicenceTextsTest {

    @Test
    fun `a table row reads as its cells and its separator row goes`() {
        val markdown = """
            | Component | Licence |
            | --- | --- |
            | PRoot | GPL-2.0-or-later ([text](LICENSES/GPL-2.0.txt)) |
        """.trimIndent()
        assertEquals("Component · Licence\nPRoot · GPL-2.0-or-later (text)", LicenceTexts.plain(markdown))
    }

    @Test
    fun `a web link keeps its address beside its words`() {
        assertEquals(
            "Source: PRoot tag v5 (https://github.com/termux/proot)",
            LicenceTexts.plain("Source: [PRoot tag `v5`](https://github.com/termux/proot)"),
        )
    }

    @Test
    fun `headings and emphasis lose their marks`() {
        assertEquals("Third-party software\n\nSource code. Every release carries it.", LicenceTexts.plain("# Third-party software\n\n**Source code.** Every release carries it."))
    }

    @Test
    fun `a paragraph wrapped over several lines reads as one`() {
        assertEquals(
            "The Helper starts them as programs of their own and does not link them.",
            LicenceTexts.plain("The Helper starts them as programs of their\nown and does not link them."),
        )
    }

    @Test
    fun `a list item starts with a bullet and keeps its wrapped lines`() {
        assertEquals(
            "Downloaded:\n\n• Debian, the system (built by https://github.com/x/y). Sources too.\n• Node.js",
            LicenceTexts.plain("Downloaded:\n\n- **Debian**, the system (built by\n  https://github.com/x/y). Sources too.\n- Node.js"),
        )
    }

    @Test
    fun `runs of blank lines fold to one`() {
        assertEquals("one\n\ntwo", LicenceTexts.plain("one\n\n\n\ntwo"))
    }
}
