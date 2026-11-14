package com.nujan.helper.core.runtime

/**
 * What `helper_doctor` checks, and how it reads the answer. The [PROBE] runs inside Linux and prints one `key=value` line
 * per check, so a check that cannot run leaves its line out rather than stopping the rest; [read] turns the lines into
 * checks, each problem with the step that fixes it.
 */
object HelperDoctor {

    data class Check(val name: String, val ok: Boolean, val detail: String, val fix: String? = null)

    data class Report(val checks: List<Check>, val release: String?, val tools: Map<String, String>) {
        val ok: Boolean get() = checks.all { it.ok }
    }

    /** The longest the probe may run; a name lookup that hangs is cut at six seconds inside it. */
    const val TIMEOUT_MS = 30_000L

    /** The runtimes and compilers whose versions the report names when they are installed. */
    val TOOLS = listOf("node", "npm", "python3", "gcc", "cmake", "git", "sqlite3")

    val PROBE: String = """
        . /etc/os-release 2>/dev/null && echo "release=${'$'}PRETTY_NAME"
        if timeout 6 getent hosts deb.debian.org >/dev/null 2>&1; then echo names=ok; else echo names=fail; fi
        if [ -n "$(dpkg --audit 2>/dev/null)" ]; then echo packages=broken; else echo packages=ok; fi
        if [ -e /var/lib/dpkg/lock-frontend ] && ! flock -n /var/lib/dpkg/lock-frontend true 2>/dev/null; then echo installer=busy; else echo installer=free; fi
        for t in ${TOOLS.joinToString(" ")}; do
          command -v "${'$'}t" >/dev/null 2>&1 && echo "tool.${'$'}t=$("${'$'}t" --version 2>&1 | head -n 1)"
        done
        echo finished=1
    """.trimIndent()

    /**
     * The report for a probe that printed [output], or that could not start at all ([startError]), on a phone with
     * [freeBytes] free.
     */
    fun read(output: String?, startError: String?, freeBytes: Long): Report {
        val values = output.orEmpty().lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
        val checks = mutableListOf<Check>()
        val finished = values["finished"] == "1"
        checks += when {
            startError != null -> Check("linux", false, "Linux did not start: $startError", "Open the NuJan Helper app and check its Linux setup.")
            !finished -> Check(
                "linux", false, "Linux started but stopped before the checks finished: ${output.orEmpty().takeLast(MAX_DETAIL).trim()}",
                "Open the NuJan Helper app and check its Linux setup.",
            )
            else -> Check("linux", true, "Linux runs" + (values["release"]?.let { ": $it" } ?: "."))
        }
        checks += if (freeBytes < InstallRoom.RESERVE_BYTES) {
            Check(
                "storage", false, "The phone has ${Storage.humanBytes(freeBytes)} free; installs need ${Storage.humanBytes(InstallRoom.RESERVE_BYTES)} left over.",
                "Free space on the phone, or remove packages or workspaces that are no longer needed.",
            )
        } else {
            Check("storage", true, "${Storage.humanBytes(freeBytes)} free.")
        }
        if (finished) {
            checks += if (values["names"] == "ok") {
                Check("network", true, "Linux reaches the package servers by name.")
            } else {
                Check(
                    "network", false, "Linux cannot look up deb.debian.org by name.",
                    "Check the phone's connection and that the Helper's network switch is on for this app. A VPN or " +
                        "Private DNS setting can also block lookups inside Linux.",
                )
            }
            checks += if (values["packages"] == "broken") {
                Check(
                    "packages", false, "An install was interrupted and left packages half set up, so apt refuses new ones.",
                    "run_command: dpkg --configure -a && apt-get -f install -y",
                )
            } else {
                Check("packages", true, "No package is half installed.")
            }
            checks += if (values["installer"] == "busy") {
                Check(
                    "installer", false, "Another install holds the package lock.",
                    "Wait for the install job to end (job_list); a job that is no longer running releases the lock when stopped (job_stop).",
                )
            } else {
                Check("installer", true, "The package installer is free.")
            }
        }
        val tools = TOOLS.mapNotNull { tool -> values["tool.$tool"]?.let { tool to it.take(MAX_VERSION) } }.toMap()
        return Report(checks, values["release"], tools)
    }

    private const val MAX_DETAIL = 400
    private const val MAX_VERSION = 80
}
