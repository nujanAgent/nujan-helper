package com.nujan.helper.core.runtime

/**
 * Progress of the first-run bootstrap wizard: download → verify → extract → write DNS →
 * smoke. Pure data so the orchestration (`:app` BootstrapManager) can be reasoned about and the UI
 * can render a single stream of states. Stages are strictly ordered; [fraction] is a 0..1 hint for a
 * progress bar (indeterminate stages report null).
 */
sealed interface BootstrapState {
    /** Nothing done yet — the wizard shows consent + the storage estimate. */
    data object Idle : BootstrapState

    /** Streaming the tarball. [downloadedBytes]/[totalBytes] drive the bar; total may be null pre-headers. */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : BootstrapState {
        val fraction: Double? get() = totalBytes?.takeIf { it > 0 }?.let { downloadedBytes.toDouble() / it }
    }

    /** Hashing the downloaded file against [RootfsArtifact.SHA256] (indeterminate). */
    data object Verifying : BootstrapState

    /**
     * Decompressing and piping into `proot ... tar x`. [bytesExtracted] is the count of
     * *decompressed* bytes streamed so far (a monotonic progress signal against [RootfsArtifact.UNCOMPRESSED_BYTES]); the
     * uncompressed total isn't known exactly up front, so the bar is best-effort.
     */
    data class Extracting(val bytesExtracted: Long) : BootstrapState

    /** Writing `rootfs/etc/resolv.conf` from the live device resolvers (indeterminate). */
    data object ConfiguringNetwork : BootstrapState

    /** Running the `proot -r rootfs sh -c 'echo BOOTOK && uname -m'` smoke check (indeterminate). */
    data object SmokeTesting : BootstrapState

    /** A fresh rootfs installing [BootstrapPlan.BASICS] with apt (indeterminate). */
    data object InstallingBasics : BootstrapState

    /**
     * Rootfs is bootable. [arch] is the `uname -m` reported from inside (expected `aarch64`). [basicsNote] says why a
     * fresh rootfs went without [BootstrapPlan.BASICS]; the toolbox works without them, and a profile installs what it
     * needs.
     */
    data class Ready(val arch: String, val basicsNote: String? = null) : BootstrapState

    /**
     * A stage failed. [stage] is where, or null for an error outside the stages; [reason] says what went wrong in
     * sentences a person can act on, and [detail] is what the failing step reported, kept for diagnosis. The wizard
     * offers retry.
     */
    data class Failed(val stage: Stage?, val reason: String, val detail: String? = null) : BootstrapState

    /** The setup's stages, in order, as a failure names them. */
    enum class Stage {
        /** Checking the phone has room, and unpacking the program that runs Linux. */
        PREPARE,
        DOWNLOAD,
        VERIFY,
        EXTRACT,
        /** Writing Linux's network settings. */
        NETWORK,
        /** Starting Linux once to see that it runs. */
        SMOKE,
    }
}
