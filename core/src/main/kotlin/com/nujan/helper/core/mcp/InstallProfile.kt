package com.nujan.helper.core.mcp

/**
 * A named provisioning profile (`pkg_install_profile`).
 *
 * The base Debian rootfs is deliberately minimal (bash + python3 + apt, no dev toolchain). A profile
 * is a **curated, reviewed** shell recipe that installs one coherent toolchain into it. This is the
 * whole reason `pkg_install_profile` exists as a distinct tool rather than folding into `run_command`:
 * only these named recipes are offered, so provisioning is auditable and repeatable, while arbitrary
 * `apt` lines stay *visibly shell* through `run_command` (and its `shell` capability + ASK tier).
 *
 * Every recipe is **idempotent** (safe to re-run — it no-ops when the toolchain is already present),
 * leaves no downloaded packages behind (`apt-get clean`), and ends by printing [okMarker], so a caller
 * polling `job_tail` can confirm success from the job output without a second probe. [provides] lists
 * the binaries the profile should yield, for `helper_status` reporting and post-install verification.
 */
data class InstallProfile(
    val id: String,
    val summary: String,
    val provides: List<String>,
    val script: String,
    /** About how much of the phone's storage the install takes while it runs, downloads and package cache included. */
    val needsBytes: Long,
    /** What the toolchain is called on the Helper's screens, such as "Python tools"; [summary] is written for the model. */
    val title: String = id,
) {
    /** Success sentinel the [script] prints on completion, e.g. `PROFILE_WEB_OK`. */
    val okMarker: String get() = "PROFILE_${id.uppercase().replace('-', '_')}_OK"

    /**
     * What a run of the [script] is doing, in words for the person and for a refusal: installing the toolchain, or
     * checking it when its programs are all there already.
     */
    fun runLabel(installed: Boolean): String = if (installed) "Checking $title" else "Installing $title"

    /**
     * Whether every program this profile provides is in the Linux system at [rootfsDir]. Its links point inside that
     * system, so a link counts as there without following it.
     */
    fun isInstalledIn(rootfsDir: String): Boolean = provides.all { program ->
        listOf("usr/local/bin", "usr/bin", "bin").any { dir ->
            java.nio.file.Files.exists(java.io.File(rootfsDir, "$dir/$program").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }
    }
}

/**
 * The registry of installable profiles: the **web/full-stack** toolchain, **python**, **native** C and C++
 * builds, **embedded** board tools, and **adb** for other Android devices.
 */
object InstallProfiles {

    /**
     * Full-stack web development: Node.js [NODE_VERSION] (a long-term-support release) from nodejs.org, checked
     * against its pinned SHA-256 before it is unpacked, plus `git`, a C/C++ toolchain (`build-essential`) for native npm
     * modules, `sqlite3` for an app's database, and `unzip`/`xz-utils` for template archives. npm installs global
     * programs into /usr/local, which is on the toolbox's PATH. Bump the version and its checksum here deliberately,
     * never automatically.
     */
    val WEB = InstallProfile(
        id = "web",
        summary = "Full-stack web toolchain: Node.js $NODE_VERSION LTS + npm, git, build-essential, sqlite3, unzip.",
        provides = listOf("node", "npm", "git", "make", "unzip", "sqlite3"),
        script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends \
                ca-certificates curl git build-essential sqlite3 unzip xz-utils
            apt-get clean
            if [ "${'$'}(node --version 2>/dev/null)" != "v$NODE_VERSION" ]; then
                mkdir -p /root/.cache/nujan
                curl -fsSL "$NODE_URL" -o /root/.cache/nujan/node.tar.xz
                echo "$NODE_SHA256  /root/.cache/nujan/node.tar.xz" | sha256sum -c
                rm -rf /opt/node
                mkdir -p /opt/node
                tar -xJf /root/.cache/nujan/node.tar.xz -C /opt/node --strip-components=1
                rm -f /root/.cache/nujan/node.tar.xz
                for b in node npm npx corepack; do ln -sf /opt/node/bin/${'$'}b /usr/local/bin/${'$'}b; done
                npm config set prefix /usr/local --location=global
            fi
            echo "node    ${'$'}(node --version)"
            echo "npm     ${'$'}(npm --version)"
            echo "git     ${'$'}(git --version)"
            echo "sqlite3 ${'$'}(sqlite3 --version | cut -d' ' -f1)"
            echo PROFILE_WEB_OK
        """.trimIndent(),
        needsBytes = 700L * MIB,
        title = "web development tools",
    )

    /**
     * Python projects: pip, venv and the headers a package that builds C code needs, from Debian. pip does not install
     * into Debian's own Python, so the recipe ends by saying how to give a project its own environment. A package that
     * compiles C code also needs the [NATIVE] compiler.
     */
    val PYTHON = InstallProfile(
        id = "python",
        summary = "Python projects: pip, venv and Python's headers; each project gets its own environment.",
        provides = listOf("python3", "pip3", "python3-config"),
        script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            if [ ! -x /usr/bin/pip3 ] || [ ! -x /usr/bin/python3-config ] || ! python3 -c "import ensurepip" 2>/dev/null; then
                apt-get update
                apt-get install -y --no-install-recommends python3 python3-pip python3-venv python3-dev
            fi
            apt-get clean
            echo "python  $(python3 --version 2>&1)"
            echo "pip     $(pip3 --version | cut -d' ' -f2)"
            echo "pip does not install into Debian's own Python; give each project an environment: python3 -m venv .venv, then .venv/bin/pip install NAME"
            echo PROFILE_PYTHON_OK
        """.trimIndent(),
        needsBytes = 200L * MIB,
        title = "Python tools",
    )

    /** C and C++ builds: gcc and g++ with make (`build-essential`), CMake, Ninja and pkg-config, from Debian. */
    val NATIVE = InstallProfile(
        id = "native",
        summary = "C and C++ builds: gcc, g++, make, cmake, ninja and pkg-config.",
        provides = listOf("gcc", "g++", "make", "cmake", "ninja", "pkg-config"),
        script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            if ! command -v g++ >/dev/null || ! command -v cmake >/dev/null || ! command -v ninja >/dev/null || ! command -v pkg-config >/dev/null; then
                apt-get update
                apt-get install -y --no-install-recommends build-essential cmake ninja-build pkg-config
            fi
            apt-get clean
            echo "gcc     $(gcc -dumpfullversion)"
            echo "cmake   $(cmake --version | head -1 | cut -d' ' -f3)"
            echo "ninja   $(ninja --version)"
            echo PROFILE_NATIVE_OK
        """.trimIndent(),
        needsBytes = 520L * MIB,
        title = "C and C++ build tools",
    )

    // Pinned Node.js LTS from nodejs.org; the checksum is the release's own SHASUMS256.txt line.
    const val NODE_VERSION = "24.22.1"
    const val NODE_URL = "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz"
    const val NODE_SHA256 = "dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e"

    /**
     * Embedded boards on USB: esptool (identifies and flashes Espressif chips), mpremote (MicroPython boards) and
     * pyserial's miniterm (a serial monitor), pinned, in their own Python environment so the system Python stays as
     * Debian ships it. A program in the rootfs cannot open the phone's USB devices itself; these reach a board through
     * the URL `usb_serial_open` gives, [com.nujan.helper.core.serial.BoardSocket.URL], which the toolbox's Python routes
     * to the Helper's private socket.
     */
    val EMBEDDED = InstallProfile(
        id = "embedded",
        summary = "Boards on USB: esptool, mpremote and miniterm, for the port usb_serial_open serves.",
        provides = listOf("esptool", "mpremote", "miniterm"),
        script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            if [ ! -x /opt/embedded/bin/esptool ]; then
                apt-get update
                apt-get install -y --no-install-recommends python3 python3-venv ca-certificates
                python3 -m venv /opt/embedded
                /opt/embedded/bin/pip install --no-cache-dir esptool==$ESPTOOL_VERSION mpremote==$MPREMOTE_VERSION pyserial==$PYSERIAL_VERSION
            fi
            apt-get clean
            ln -sf /opt/embedded/bin/esptool /usr/local/bin/esptool
            ln -sf /opt/embedded/bin/mpremote /usr/local/bin/mpremote
            ln -sf /opt/embedded/bin/pyserial-miniterm /usr/local/bin/miniterm
            echo "esptool  $(esptool version 2>&1 | tail -1)"
            echo "mpremote $(mpremote --version 2>&1)"
            echo PROFILE_EMBEDDED_OK
        """.trimIndent(),
        needsBytes = 150L * MIB,
        title = "board tools",
    )

    const val ESPTOOL_VERSION = "5.6.0"
    const val MPREMOTE_VERSION = "1.29.0"
    const val PYSERIAL_VERSION = "3.5"

    /**
     * adb for other Android devices on the network, one that pairs with a code, which wireless debugging on Android 11 and
     * later requires. Debian 13's own adb pairs; the adb Debian 12 ships predates pairing, so on Debian 12 the recipe takes
     * adb from bookworm-backports, and on any other release it removes that source so apt uses the release's own
     * packages. A pairing lasts, since the device keeps trusting the rootfs's adb key; the connection does not, because
     * the adb server a command starts stops when the command ends, so each command connects before it acts.
     */
    val ADB = InstallProfile(
        id = "adb",
        summary = "adb for other Android devices on this network: pair with a code (Android 11 and later), connect, " +
            "shell, install, push, pull.",
        provides = listOf("adb"),
        script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            codename=$(. /etc/os-release && echo "${'$'}VERSION_CODENAME")
            if [ "${'$'}codename" != bookworm ]; then
                rm -f /etc/apt/sources.list.d/bookworm-backports.list
            fi
            if ! adb help 2>&1 | grep -q "pair HOST"; then
                if [ "${'$'}codename" = bookworm ]; then
                    echo "deb http://deb.debian.org/debian bookworm-backports main" > /etc/apt/sources.list.d/bookworm-backports.list
                    apt-get update
                    apt-get install -y --no-install-recommends -t bookworm-backports adb
                else
                    apt-get update
                    apt-get install -y --no-install-recommends adb
                fi
            fi
            apt-get clean
            adb help 2>&1 | grep -q "pair HOST"
            echo "adb     $(adb version | sed -n 2p)"
            echo "Pair once with the address and code the device shows under Wireless debugging, Pair device with pairing code: adb pair HOST:PORT CODE"
            echo "Connect in the same command that uses the device: adb connect HOST:PORT && adb -s HOST:PORT shell getprop ro.product.model"
            echo PROFILE_ADB_OK
        """.trimIndent(),
        needsBytes = 50L * MIB,
        title = "adb",
    )

    private const val MIB = 1024L * 1024

    private val byId: Map<String, InstallProfile> =
        listOf(WEB, PYTHON, NATIVE, EMBEDDED, ADB).associateBy { it.id }

    /** Known profile ids, sorted — surfaced in the tool schema and unknown-profile errors. */
    val ids: List<String> get() = byId.keys.sorted()

    fun get(id: String): InstallProfile? = byId[id]
}
