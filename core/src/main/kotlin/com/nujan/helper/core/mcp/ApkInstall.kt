package com.nujan.helper.core.mcp

/**
 * Pure helpers for `install_apk`. The tool hands a workspace APK to Android's package
 * installer in a session, and Android asks the person to confirm: that confirmation is the final, unforgeable gate.
 * Validation, the outcomes and their wording live here (unit-testable in `:core`); the session itself lives in `:app`.
 */
object ApkInstall {
    /** The MIME type the package installer keys on. */
    const val MIME = "application/vnd.android.package-archive"

    /** True iff [name] looks like an APK by extension (case-insensitive). */
    fun isApkName(name: String): Boolean =
        name.substringAfterLast('.', "").equals("apk", ignoreCase = true)

    /** Where an install stands, as the tool reports it. */
    sealed interface Outcome {
        data class Installed(val packageName: String?) : Outcome
        data class Failed(val reason: String) : Outcome
        /** Android is waiting for the person to confirm, in [session]. */
        data class WaitingForPerson(val session: Int) : Outcome
    }

    /** A final [outcome] as one line the Helper keeps between runs; null for one that is not final. */
    fun encode(outcome: Outcome): String? = when (outcome) {
        is Outcome.Installed -> "installed\t${outcome.packageName.orEmpty()}"
        is Outcome.Failed -> "failed\t${outcome.reason}"
        is Outcome.WaitingForPerson -> null
    }

    /** The outcome [encode] wrote as [line]; null for a line it did not write. */
    fun decode(line: String): Outcome? {
        val detail = line.substringAfter('\t', "")
        return when (line.substringBefore('\t', "")) {
            "installed" -> Outcome.Installed(detail.ifEmpty { null })
            "failed" -> Outcome.Failed(detail)
            else -> null
        }
    }

    // PackageInstaller's status codes; their values are public, stable API.
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0

    /** What a final status other than success from Android's package installer means, in words a model can act on. */
    fun failure(status: Int, message: String?): String {
        val why = when (status) {
            2 -> "Android or a device policy blocked it"
            3 -> "the person declined it, or it was cancelled"
            4 -> "the APK is not valid: damaged, unsigned or not an APK"
            5 -> "it conflicts with an app already installed, most often one signed with a different key"
            6 -> "the phone does not have enough storage for it"
            7 -> "it is not compatible with this phone (its ABI, its minimum Android version or a required feature)"
            8 -> "the install took too long and timed out"
            else -> "Android did not install it"
        }
        return if (message.isNullOrBlank()) why else "$why ($message)"
    }
}
