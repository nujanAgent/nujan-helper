// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

/**
 * What the platform reports about the app behind a Binder call (SPEC.md 6.1): the packages of its uid and, for
 * that package, the SHA-256 digests of its current signing certificates and of the ones it rotated from.
 * PackageManager supplies these; nothing a caller says about itself is one of them.
 */
data class CallerFacts(
    val uid: Int,
    val packages: List<String>,
    val currentSigners: Set<String>,
    /** Earlier signers in the key-rotation lineage, oldest first; empty without rotation. */
    val pastSigners: List<String> = emptyList(),
)

/** The answer to [CallerChecks.check]. */
sealed interface CallerVerdict {
    /** The caller is who the platform says; [signer] is what the grant should hold from now on. */
    data class Accepted(val packageName: String, val signer: String) : CallerVerdict

    /** Refused before any grant is looked at: answer [OpenStatus.IDENTITY_REFUSED]. */
    data class Refused(val reason: String) : CallerVerdict

    /** The package is signed by a key outside the recorded lineage: a different app under the same name. */
    data class SignerChanged(val packageName: String, val recorded: String, val current: String) : CallerVerdict
}

/** The caller checks a provider runs on every call (SPEC.md 6.1), over facts the platform supplied. */
object CallerChecks {
    /**
     * Checks [caller] against [providerUid] and the signer the grant [recorded], if any. A multi-signer package
     * is identified by its whole set of signers. A recorded signer that the current one rotated from is accepted
     * and moved forward to the current signer (the ratchet).
     */
    fun check(caller: CallerFacts, providerUid: Int, recorded: String?): CallerVerdict {
        if (caller.uid == providerUid) return CallerVerdict.Refused("the provider's own uid")
        val packageName = caller.packages.singleOrNull()
            ?: return CallerVerdict.Refused(if (caller.packages.isEmpty()) "no package for this uid" else "a shared uid")
        if (caller.currentSigners.isEmpty()) return CallerVerdict.Refused("no signing certificate")
        val current = signerOf(caller.currentSigners)
        return when (recorded) {
            null, current -> CallerVerdict.Accepted(packageName, current)
            in caller.pastSigners -> CallerVerdict.Accepted(packageName, current)
            else -> CallerVerdict.SignerChanged(packageName, recorded, current)
        }
    }

    /** One string for a signer set: the digests sorted and joined, so the same set always reads the same. */
    fun signerOf(digests: Set<String>): String = digests.map { it.lowercase() }.sorted().joinToString(",")
}

/**
 * A provider's trusted hosts (SPEC.md 6.4 item 7): (package, signer digest) pairs committed in its source. A
 * release provider build never treats a debug-signed caller as pinned, whatever its digest.
 */
class TrustedHosts(private val pins: Set<Pair<String, String>>, private val releaseBuild: Boolean) {
    init {
        require(pins.all { (_, digest) -> digest == digest.lowercase() }) { "pin digests are lowercase hex" }
    }

    /** Whether [packageName] signed by [signer] is pinned; [certificateSubject] is the current certificate's subject. */
    fun isPinned(packageName: String, signer: String, certificateSubject: String): Boolean {
        if (releaseBuild && certificateSubject.contains("CN=Android Debug")) return false
        return (packageName to signer.lowercase()) in pins
    }
}
