package com.nujan.helper.core.runtime

import com.nujan.helper.core.serial.BoardSocket

/**
 * Filesystem layout of the proot runtime, all under the app's private files dir. Pure and
 * Android-free so the paths + the proot invocation (see [ProotCommand]) are unit-testable on the
 * JVM.
 */
class RuntimeLayout(val filesDir: String) {
    private val base = filesDir.trimEnd('/')

    /** Unpacked proot binary + loader + support libs (from the APK assets). */
    val bootstrapDir = "$base/bootstrap"
    val prootBin = "$bootstrapDir/bin/proot"
    val loader = "$bootstrapDir/libexec/proot/loader"
    val loader32 = "$bootstrapDir/libexec/proot/loader32"
    val libDir = "$bootstrapDir/lib"

    /** The Linux rootfs (downloaded + extracted by the bootstrap wizard). */
    val rootfsDir = "$base/rootfs"

    /** `resolv.conf` inside the rootfs — Android has none, so the DNS manager writes it. */
    val rootfsResolvConf = "$rootfsDir/etc/resolv.conf"

    /** Staging area for the in-progress download (`.part` for resume) before it becomes the rootfs. */
    val downloadDir = "$base/downloads"
    val rootfsArchive = "$downloadDir/rootfs.tar"
    val rootfsArchivePart = "$rootfsArchive.part"

    /** Present while a fresh rootfs still has [BootstrapPlan.BASICS] to install. */
    val basicsPending = "$downloadDir/basics-pending"

    /** Present from the start of an extraction until it has finished, so a rootfs cut off part-way is extracted again. */
    val extractPending = "$downloadDir/extract-pending"

    /** True once the rootfs has been extracted far enough to boot (its `/bin/sh` exists). */
    val rootfsShell = "$rootfsDir/bin/sh"

    /** Project workspaces — the only tree filesystem tools may touch. */
    val workspacesDir = "$base/workspaces"

    /** Per-job logs (`<id>.log`) + the append-only `audit.log`. */
    val jobsDir = "$base/jobs"

    /** Built artifacts staged for the user (APKs, zips) before share/install. */
    val exportsDir = "$base/exports"

    /** proot's scratch dir; must exist and be writable before launch. */
    val tmpDir = "$base/tmp"

    /** The stand-ins for the /proc files Android keeps from apps ([ProcStandIns]), bound over them in the rootfs. */
    val procDir = "$base/proc"

    /** What the Helper serves the toolbox, bound in the rootfs at [BoardSocket.GUEST_DIR]: the board socket and its route. */
    val runDir = "$base/run"
    val boardSocket = "$runDir/${BoardSocket.FILE_NAME}"
    val runPythonDir = "$runDir/python"

    /** Files that must be marked executable after unpacking. */
    val executables: List<String> get() = listOf(prootBin, loader, loader32)

    /** Dirs the runtime creates on bootstrap. */
    val runtimeDirs: List<String> get() = listOf(bootstrapDir, libDir, workspacesDir, tmpDir, runDir)

    /** Dirs the bootstrap wizard creates before download/extract. */
    val bootstrapDirs: List<String> get() = listOf(downloadDir, rootfsDir, tmpDir)
}
