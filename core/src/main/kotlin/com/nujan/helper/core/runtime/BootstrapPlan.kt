package com.nujan.helper.core.runtime

/**
 * The two load-bearing proot invocations of the bootstrap wizard, kept pure so their exact flags are
 * pinned by unit tests (a wrong flag here only fails on-device — the class of bug we most want to
 * catch on the JVM).
 */
object BootstrapPlan {

    /**
     * Extract the (already xz-decompressed) tar stream into [RuntimeLayout.rootfsDir].
     *
     * Host-fs mode (no `-r`): we run the device's own `/system/bin/toybox tar` UNDER proot with
     * `-0 --link2symlink` because Android SELinux blocks hardlinks in app storage and dpkg rootfs
     * images are full of them — proot turns hardlinks into `.l2s.*` symlinks and
     * `-0` fakes root ownership. The tar bytes are fed to stdin (`-f -`); the app decompresses xz
     * itself (there is no on-device `xz`).
     */
    fun extract(layout: RuntimeLayout): ProotCommand.Spec = ProotCommand.Spec(
        layout = layout,
        command = listOf("/system/bin/toybox", "tar", "x", "-C", layout.rootfsDir, "-f", "-"),
        rootEmulation = true,
        link2symlink = true,
    )

    /** `/etc/hosts` for a rootfs without one: Debian's minbase leaves it to an installer, and dev servers use `localhost`. */
    const val HOSTS = "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n"

    /** What a fresh rootfs installs after it first boots, so it matches the toolbox the earlier, larger image gave. */
    val BASICS = listOf("ca-certificates", "curl", "netbase", "procps", "python3")

    /** About what installing [BASICS] adds to a fresh rootfs while apt runs, its lists and downloads included. */
    const val BASICS_BYTES = 120L * 1024 * 1024

    /**
     * The most of the phone's storage the first-run setup holds at once: the unpacked rootfs, with the download beside
     * it until it is unpacked and deleted, and then the [BASICS].
     */
    val SETUP_BYTES: Long = RootfsArtifact.EXTRACTED_BYTES + maxOf(RootfsArtifact.COMPRESSED_BYTES, BASICS_BYTES)

    /**
     * Installs [BASICS] the way a job runs: inside the rootfs, root emulated, hardlinks as symlinks (dpkg needs that),
     * the system dirs bound, and what apt leaves running stopped when it ends. A run cut off part-way leaves packages
     * half-configured, and apt then refuses to install anything, so dpkg first finishes what it left.
     */
    fun basics(layout: RuntimeLayout): ProotCommand.Spec = ProotCommand.Spec(
        layout = layout,
        command = GuestEnv.envPrefix("dumb") + listOf(
            "/bin/sh", "-c",
            "DEBIAN_FRONTEND=noninteractive dpkg --configure -a; " +
                "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
                BASICS.joinToString(" "),
        ),
        rootfs = layout.rootfsDir,
        rootEmulation = true,
        link2symlink = true,
        killOnExit = true,
        binds = listOf("/proc", "/dev", "/sys"),
        workdir = "/root",
    )

    /**
     * The bootability smoke check: `proot -r rootfs /bin/sh -c 'echo BOOTOK && uname -m'`.
     * Guest mode with `/proc /dev /sys` bound and `-0`, matching how real jobs will enter the rootfs.
     * A [Ready][BootstrapState.Ready] verdict requires BOTH `BOOTOK` and `aarch64` in the output.
     */
    fun smoke(layout: RuntimeLayout): ProotCommand.Spec = ProotCommand.Spec(
        layout = layout,
        // Set a guest PATH inline: proot passes the app's env through, so without this the shell
        // inherits Android's PATH (/system/bin) and `uname` isn't found (exit 127).
        // Real jobs and sessions construct a full guest env (env -i HOME/PATH/LANG, GuestEnv).
        command = listOf("/bin/sh", "-c", "export PATH=/usr/sbin:/usr/bin:/sbin:/bin; echo BOOTOK && uname -m"),
        rootfs = layout.rootfsDir,
        rootEmulation = true,
        binds = listOf("/proc", "/dev", "/sys"),
        workdir = "/root",
    )
}
