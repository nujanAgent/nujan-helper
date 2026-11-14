// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A provider believes only the platform about who calls, and a new key is a new app unless it rotated from the old one. */
class IdentityTest {

    private val provider = 10_100
    private fun caller(vararg packages: String, signers: Set<String> = setOf("AA"), past: List<String> = emptyList()) =
        CallerFacts(uid = 10_200, packages = packages.toList(), currentSigners = signers, pastSigners = past)

    @Test
    fun `a caller with one package and a signer is accepted, and its signer is what the grant keeps`() {
        assertEquals(CallerVerdict.Accepted("com.nujan.app", "aa"), CallerChecks.check(caller("com.nujan.app"), provider, null))
        assertEquals(CallerVerdict.Accepted("com.nujan.app", "aa"), CallerChecks.check(caller("com.nujan.app"), provider, "aa"))
    }

    @Test
    fun `the provider's own uid, a shared uid, no package or no signer is refused`() {
        assertTrue(CallerChecks.check(caller("x").copy(uid = provider), provider, null) is CallerVerdict.Refused)
        assertTrue(CallerChecks.check(caller("a", "b"), provider, null) is CallerVerdict.Refused)
        assertTrue(CallerChecks.check(caller(), provider, null) is CallerVerdict.Refused)
        assertTrue(CallerChecks.check(caller("a", signers = emptySet()), provider, null) is CallerVerdict.Refused)
    }

    @Test
    fun `a signer outside the recorded one's lineage is a different app`() {
        assertEquals(
            CallerVerdict.SignerChanged("com.nujan.app", recorded = "aa", current = "bb"),
            CallerChecks.check(caller("com.nujan.app", signers = setOf("BB")), provider, "aa"),
        )
    }

    @Test
    fun `a key rotated from the recorded one is accepted and the grant moves to the new key`() {
        assertEquals(
            CallerVerdict.Accepted("com.nujan.app", "bb"),
            CallerChecks.check(caller("com.nujan.app", signers = setOf("bb"), past = listOf("aa")), provider, "aa"),
        )
    }

    @Test
    fun `several signers are one identity, whatever their order`() {
        assertEquals(CallerChecks.signerOf(setOf("bb", "AA")), CallerChecks.signerOf(setOf("aa", "bb")))
    }

    @Test
    fun `a release provider never pins a debug-signed caller`() {
        val pins = setOf("com.nujan.app" to "aa")
        assertTrue(TrustedHosts(pins, releaseBuild = false).isPinned("com.nujan.app", "AA", "CN=Android Debug,O=Android,C=US"))
        assertFalse(TrustedHosts(pins, releaseBuild = true).isPinned("com.nujan.app", "aa", "CN=Android Debug,O=Android,C=US"))
        assertTrue(TrustedHosts(pins, releaseBuild = true).isPinned("com.nujan.app", "aa", "CN=NuJan"))
        assertFalse(TrustedHosts(pins, releaseBuild = true).isPinned("com.other.app", "aa", "CN=NuJan"))
    }
}
