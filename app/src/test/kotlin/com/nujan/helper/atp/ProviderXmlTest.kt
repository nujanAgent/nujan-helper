package com.nujan.helper.atp

import com.nujan.helper.HelperLinks
import com.nujan_agent.atp.provider.AtpProviderXml
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Helper's provider XML follows ATP's rules for it (SPEC.md 5.2), which hosts read before they bind, and its privacy
 * policy and contact are the repository pages the Helper's own screens open.
 */
class ProviderXmlTest {

    private val xml = java.io.File("src/main/res/xml/atp_provider.xml").readText()

    @Test
    fun `the Helper's provider XML breaks none of the spec's rules`() {
        assertEquals(emptyList<String>(), AtpProviderXml.problems(xml))
    }

    @Test
    fun `its privacy policy and contact are the Helper's repository pages`() {
        assertEquals(HelperLinks.PRIVACY, Regex("""privacyPolicy="([^"]+)"""").find(xml)?.groupValues?.get(1))
        assertEquals(HelperLinks.ISSUES, Regex("""contact="([^"]+)"""").find(xml)?.groupValues?.get(1))
    }
}
