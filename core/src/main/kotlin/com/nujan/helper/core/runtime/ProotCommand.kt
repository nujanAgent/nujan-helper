package com.nujan.helper.core.runtime

/**
 * Builds the proot argv + environment — the single load-bearing invocation the whole runtime rests
 * on. Kept pure so the exact flags/env are locked by unit
 * tests: a silent change here (a missing `-b /proc`, a wrong loader var) is the kind of bug that
 * only shows up on-device, so we pin it on the JVM instead.
 *
 * Two shapes:
 *  - host-fs mode (no [rootfs]) — run a device binary under proot, e.g. a self-test.
 *  - guest mode ([rootfs] set, usually with [rootEmulation] + [link2symlink]) — run inside the
 *    Debian/Alpine rootfs.
 */
object ProotCommand {

    data class Spec(
        val layout: RuntimeLayout,
        /** The command to run, e.g. ["/system/bin/sh","-c","echo BOOTOK"] or ["/bin/bash", script]. */
        val command: List<String>,
        /** `-r <rootfs>`; null = host-fs mode (run against the device's own `/`). */
        val rootfs: String? = null,
        /** `-0` root emulation (needed for apt/dpkg/file-ownership inside a guest). */
        val rootEmulation: Boolean = false,
        /** `--link2symlink` — Android SELinux blocks hardlinks; required to extract/run Debian. */
        val link2symlink: Boolean = false,
        /**
         * `--kill-on-exit`: when [command] ends, stop whatever it left running. Without it proot waits for every
         * process the command started, so a command that leaves a server in the background (each `adb` command starts
         * adb's own) keeps proot running until something kills it.
         */
        val killOnExit: Boolean = false,
        /** `-b src[:dst]` bind mounts (e.g. "/proc", "/dev", "/sys"). */
        val binds: List<String> = emptyList(),
        /** `-w <dir>` initial working directory. */
        val workdir: String? = null,
    )

    /** The proot process argv. */
    fun argv(spec: Spec): List<String> {
        val a = ArrayList<String>()
        a += spec.layout.prootBin
        if (spec.rootEmulation) a += "-0"
        if (spec.link2symlink) a += "--link2symlink"
        if (spec.killOnExit) a += "--kill-on-exit"
        spec.rootfs?.let { a += "-r"; a += it }
        for (b in spec.binds) { a += "-b"; a += b }
        spec.workdir?.let { a += "-w"; a += it }
        a += spec.command
        return a
    }

    /**
     * The environment proot needs. proot's ELF interpreter is the system linker, so `libc.so`
     * resolves automatically; we supply the loader paths, the bundled support libs via
     * `LD_LIBRARY_PATH`, and a writable scratch dir.
     */
    fun env(spec: Spec): Map<String, String> = linkedMapOf(
        "LD_LIBRARY_PATH" to spec.layout.libDir,
        "PROOT_LOADER" to spec.layout.loader,
        "PROOT_LOADER_32" to spec.layout.loader32,
        "PROOT_TMP_DIR" to spec.layout.tmpDir,
    )
}
