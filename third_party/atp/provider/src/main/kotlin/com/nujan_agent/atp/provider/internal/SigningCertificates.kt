// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.nujan_agent.atp.wire.CallerFacts
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** An installed app's signers as PackageManager reports them: SHA-256 digests in lowercase hex. */
internal data class Signers(
    /** The current signer, or every signer of an app signed by several. */
    val current: Set<String>,
    /** Signers the current one rotated from, oldest first. */
    val past: List<String>,
    /** The current certificate's subject, which tells a debug key from a release key. */
    val subject: String,
)

internal object SigningCertificates {

    /** [packageName]'s signers, or null when it is not installed or reports none. */
    fun of(pm: PackageManager, packageName: String): Signers? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            when {
                signing == null -> null
                signing.hasMultipleSigners() -> signing.apkContentsSigners.orEmpty().takeIf { it.isNotEmpty() }
                    ?.let { all -> Signers(all.map(::digest).toSet(), emptyList(), subjectOf(all.first())) }
                else -> signing.signingCertificateHistory.orEmpty().takeIf { it.isNotEmpty() }
                    // The history runs from the first signer to the current one, which is last.
                    ?.let { history -> Signers(setOf(digest(history.last())), history.dropLast(1).map(::digest), subjectOf(history.last())) }
            }
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val signatures = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            signatures.takeIf { it.isNotEmpty() }?.let { all -> Signers(all.map(::digest).toSet(), emptyList(), subjectOf(all.first())) }
        }
    } catch (missing: PackageManager.NameNotFoundException) {
        null
    }

    /** What the platform says about the app behind Binder uid [uid] (SPEC.md 6.1), and its certificate subject. */
    fun callerFacts(pm: PackageManager, uid: Int): Pair<CallerFacts, String> {
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        val signers = packages.singleOrNull()?.let { of(pm, it) }
        val facts = CallerFacts(uid, packages, signers?.current.orEmpty(), signers?.past.orEmpty())
        return facts to signers?.subject.orEmpty()
    }

    private fun digest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun subjectOf(signature: Signature): String = runCatching {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(signature.toByteArray())) as X509Certificate
        certificate.subjectX500Principal.name
    }.getOrDefault("")
}
