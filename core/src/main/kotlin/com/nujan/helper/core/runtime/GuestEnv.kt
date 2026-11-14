package com.nujan.helper.core.runtime

/**
 * The environment the rootfs sees — *constructed, never inherited*. proot passes the
 * Helper's Android environment straight through, so guest commands must be launched under
 * `/usr/bin/env -i <vars> …` to wipe Android's `PATH`/identifiers and set a clean Linux env.
 *
 * Pure + JVM-tested so the exact variable set is locked; a leak here (Android paths visible in the
 * rootfs) is a privacy and correctness bug.
 */
object GuestEnv {
    const val HOME = "/root"
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val LANG = "C.UTF-8"
    const val TMPDIR = "/tmp"
    const val DEFAULT_TERM = "xterm-256color"

    /**
     * The `env -i …` prefix that resets and rebuilds the guest environment. Prepend to the guest
     * command, e.g. `envPrefix() + listOf("/bin/bash", "-l")`. Nothing from Android survives it.
     */
    fun envPrefix(term: String = DEFAULT_TERM): List<String> = listOf(
        "/usr/bin/env", "-i",
        "HOME=$HOME",
        "PATH=$PATH",
        "TERM=$term",
        "LANG=$LANG",
        "TMPDIR=$TMPDIR",
    )

    /** The terminal a one-shot job reports: one without colours or cursor movement. */
    const val JOB_TERM = "dumb"

    /**
     * What a one-shot job adds to the guest environment. A job runs on a PTY with no one at it, so a program that pages
     * its output or asks a question waits for ever: git and man page through a pager, git asks for credentials, apt asks
     * to confirm, and test runners such as vitest stay in watch mode on a terminal. These make such programs print and
     * carry on, and keep colour codes out of the output an app reads.
     */
    val JOB_SETTINGS = listOf(
        "PAGER=cat",
        "GIT_PAGER=cat",
        "MANPAGER=cat",
        "SYSTEMD_PAGER=cat",
        "GIT_TERMINAL_PROMPT=0",
        "DEBIAN_FRONTEND=noninteractive",
        "CI=true",
        "NO_COLOR=1",
    )

    /** [envPrefix] for a one-shot job: [JOB_TERM] and [JOB_SETTINGS]. */
    fun jobPrefix(): List<String> = envPrefix(JOB_TERM) + JOB_SETTINGS
}
