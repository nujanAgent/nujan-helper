package com.nujan.helper.atp

import com.nujan_agent.atp.provider.AtpProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which apps a Helper lets in: a release Helper pins no debug-signed build and still admits any app the person
 * approves, so it is never a Helper that accepts no app at all; a debug Helper pins NuJan's development builds.
 */
class HelperPinsTest {

    private val debugSubject = "CN=Android Debug, O=Android, C=US"

    @After
    fun restore() = HelperPins.configure()

    @Test
    fun `a release Helper pins no debug-signed build and admits an app the person approves`() {
        HelperPins.configure(debugBuild = false)
        assertTrue(AtpProvider.admitsUntrusted)
        assertFalse(AtpProvider.trustedHosts.isPinned("com.nujan.app.dev", HelperPins.NUJAN_DEBUG_KEY, debugSubject))
        assertFalse(AtpProvider.trustedHosts.isPinned("com.nujan.app.fieldqa", HelperPins.NUJAN_DEBUG_KEY, debugSubject))
    }

    @Test
    fun `a debug Helper pins NuJan's development builds and admits other apps too`() {
        HelperPins.configure(debugBuild = true)
        assertTrue(AtpProvider.admitsUntrusted)
        assertTrue(AtpProvider.trustedHosts.isPinned("com.nujan.app.dev", HelperPins.NUJAN_DEBUG_KEY, debugSubject))
        assertFalse(AtpProvider.trustedHosts.isPinned("com.example.other", HelperPins.NUJAN_DEBUG_KEY, debugSubject))
    }
}
