package com.nujan.helper.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallProfileTest {

    @Test
    fun `web profile is registered and looked up by id`() {
        assertEquals(InstallProfiles.WEB, InstallProfiles.get("web"))
        assertTrue("ids should list web", InstallProfiles.ids.contains("web"))
    }

    @Test
    fun `unknown profile id resolves to null`() {
        assertNull(InstallProfiles.get("nope"))
        assertNull(InstallProfiles.get(""))
    }

    @Test
    fun `okMarker is derived from the id`() {
        assertEquals("PROFILE_WEB_OK", InstallProfiles.WEB.okMarker)
    }

    @Test
    fun `web script prints its okMarker on success so callers can confirm from job output`() {
        // Drift guard: if the recipe's echo and the derived marker diverge, verification breaks silently.
        assertTrue(InstallProfiles.WEB.script.contains(InstallProfiles.WEB.okMarker))
    }

    @Test
    fun `web profile actually provisions the full-stack toolchain`() {
        val p = InstallProfiles.WEB
        // The advertised binaries and the recipe must agree on what "web" means.
        assertTrue(p.provides.containsAll(listOf("node", "npm", "git")))
        assertTrue("installs git", p.script.contains("git"))
        assertTrue("installs a C/C++ toolchain for native modules", p.script.contains("build-essential"))
    }

    @Test
    fun `web installs a pinned Node LTS from nodejs org only after its checksum matches`() {
        val p = InstallProfiles.WEB
        assertTrue(p.script.contains(InstallProfiles.NODE_URL))
        assertTrue(InstallProfiles.NODE_URL.startsWith("https://nodejs.org/dist/v${InstallProfiles.NODE_VERSION}/"))
        val check = p.script.indexOf("${InstallProfiles.NODE_SHA256}  /root/.cache/nujan/node.tar.xz\" | sha256sum -c")
        assertTrue("verifies the pinned checksum", check > 0)
        assertTrue("unpacks only after the check", p.script.indexOf("tar -xJf") > check)
        assertTrue("never pipes a downloaded script into a shell", !Regex("curl[^\\n]*\\|\\s*(ba)?sh").containsMatchIn(p.script))
        assertTrue("global npm programs land on the toolbox's PATH", p.script.contains("npm config set prefix /usr/local"))
    }

    @Test
    fun `web script is idempotent — it skips the Node install when the pinned version is there`() {
        assertTrue(InstallProfiles.WEB.script.contains("\"${'$'}(node --version 2>/dev/null)\" != \"v${InstallProfiles.NODE_VERSION}\""))
    }

    @Test
    fun `the registry offers web, python, native, embedded and adb, and no Godot toolchain`() {
        assertEquals(listOf("adb", "embedded", "native", "python", "web"), InstallProfiles.ids)
        assertNull("Godot is NuJan's own editor app, not a toolbox profile", InstallProfiles.get("godot"))
        assertNull(InstallProfiles.get("android-export"))
    }

    @Test
    fun `adb profile installs an adb that pairs with a code and prints its marker`() {
        val p = InstallProfiles.ADB
        assertEquals(p, InstallProfiles.get("adb"))
        assertEquals("PROFILE_ADB_OK", p.okMarker)
        assertTrue(p.script.contains(p.okMarker))
        assertEquals(listOf("adb"), p.provides)
        // The check runs before installing, to skip it, and after, to fail on an adb that cannot pair.
        assertEquals(2, Regex("adb help 2>&1 \\| grep -q \"pair HOST\"").findAll(p.script).count())
        assertTrue("says to connect in the same command", p.script.contains("adb connect HOST:PORT && adb -s HOST:PORT"))
    }

    /** Debian 12's own adb cannot pair and Debian 13's can, so only a Debian 12 system takes adb from bookworm-backports. */
    @Test
    fun `adb comes from bookworm-backports only on bookworm, and from the release's own packages elsewhere`() {
        val lines = InstallProfiles.ADB.script.lines().map { it.trim() }
        assertTrue("reads the release's codename", lines.contains("codename=$(. /etc/os-release && echo \"${'$'}VERSION_CODENAME\")"))
        val onBookworm = lines.indexOf("if [ \"${'$'}codename\" = bookworm ]; then")
        val elsewhere = lines.indexOf("else")
        val end = elsewhere + lines.subList(elsewhere, lines.size).indexOf("fi")
        assertTrue("picks a source only for an adb that cannot pair yet", onBookworm > lines.indexOfFirst { it.startsWith("if ! adb help") })
        assertEquals(
            listOf(
                "echo \"deb http://deb.debian.org/debian bookworm-backports main\" > /etc/apt/sources.list.d/bookworm-backports.list",
                "apt-get update",
                "apt-get install -y --no-install-recommends -t bookworm-backports adb",
            ),
            lines.subList(onBookworm + 1, elsewhere),
        )
        assertEquals(listOf("apt-get update", "apt-get install -y --no-install-recommends adb"), lines.subList(elsewhere + 1, end))
        assertEquals("one line writes the backports source", 1, lines.count { it.contains("bookworm-backports main") })
        val notBookworm = lines.indexOf("if [ \"${'$'}codename\" != bookworm ]; then")
        assertTrue("any other release drops the backports source", notBookworm >= 0)
        assertEquals("rm -f /etc/apt/sources.list.d/bookworm-backports.list", lines[notBookworm + 1])
    }

    @Test
    fun `embedded profile installs pinned board tools in their own environment and prints its marker`() {
        val p = InstallProfiles.EMBEDDED
        assertEquals(p, InstallProfiles.get("embedded"))
        assertEquals("PROFILE_EMBEDDED_OK", p.okMarker)
        assertTrue(p.script.contains(p.okMarker))
        assertTrue(p.provides.containsAll(listOf("esptool", "mpremote", "miniterm")))
        assertTrue("pins esptool", p.script.contains("esptool==${InstallProfiles.ESPTOOL_VERSION}"))
        assertTrue("pins mpremote", p.script.contains("mpremote==${InstallProfiles.MPREMOTE_VERSION}"))
        assertTrue("pins pyserial", p.script.contains("pyserial==${InstallProfiles.PYSERIAL_VERSION}"))
        assertTrue("keeps the system Python as Debian ships it", p.script.contains("python3 -m venv /opt/embedded"))
        assertTrue("skips the install when it is already there", p.script.contains("[ ! -x /opt/embedded/bin/esptool ]"))
    }

    @Test
    fun `web gives an app's database its command line`() {
        val p = InstallProfiles.WEB
        assertTrue(p.provides.contains("sqlite3"))
        assertTrue(Regex("apt-get install -y --no-install-recommends \\\\\\s+[^\\n]*\\bsqlite3\\b").containsMatchIn(p.script))
    }

    @Test
    fun `python profile installs pip, venv and the headers, and says how to give a project its own environment`() {
        val p = InstallProfiles.PYTHON
        assertEquals(p, InstallProfiles.get("python"))
        assertEquals("PROFILE_PYTHON_OK", p.okMarker)
        assertTrue(p.script.contains(p.okMarker))
        assertEquals(listOf("python3", "pip3", "python3-config"), p.provides)
        assertTrue(p.script.contains("apt-get install -y --no-install-recommends python3 python3-pip python3-venv python3-dev"))
        assertTrue("skips the install when it is already there", p.script.contains("if [ ! -x /usr/bin/pip3 ]"))
        assertTrue("says pip installs into a project's environment", p.script.contains("python3 -m venv .venv, then .venv/bin/pip install"))
    }

    @Test
    fun `native profile installs the C and C++ build tools`() {
        val p = InstallProfiles.NATIVE
        assertEquals(p, InstallProfiles.get("native"))
        assertEquals("PROFILE_NATIVE_OK", p.okMarker)
        assertTrue(p.script.contains(p.okMarker))
        assertEquals(listOf("gcc", "g++", "make", "cmake", "ninja", "pkg-config"), p.provides)
        assertTrue(p.script.contains("apt-get install -y --no-install-recommends build-essential cmake ninja-build pkg-config"))
    }

    /** apt keeps what it downloaded unless told otherwise; on a phone that is storage the person needs. */
    @Test
    fun `every profile leaves no downloaded packages behind`() {
        InstallProfiles.ids.map { InstallProfiles.get(it)!! }.forEach { p ->
            val clean = p.script.indexOf("apt-get clean")
            assertTrue("${p.id} cleans apt's downloads", clean > 0)
            assertTrue("${p.id} cleans after it installs", clean > p.script.lastIndexOf("apt-get install"))
        }
    }

    @Test
    fun `an okMarker turns a dash in the id into an underscore`() {
        assertEquals("PROFILE_A_B_OK", InstallProfile(id = "a-b", summary = "", provides = emptyList(), script = "", needsBytes = 0).okMarker)
    }

    /** The Helper's screens name a profile's run by what it does, never by its script or its id. */
    @Test
    fun `a profile's run is named for the person, installing it or checking it`() {
        assertEquals("Installing Python tools", InstallProfiles.PYTHON.runLabel(installed = false))
        assertEquals("Checking Python tools", InstallProfiles.PYTHON.runLabel(installed = true))
        assertEquals("Installing web development tools", InstallProfiles.WEB.runLabel(installed = false))
        InstallProfiles.ids.map { InstallProfiles.get(it)!! }.forEach { p ->
            assertEquals(p.id, "Installing ${p.title}", p.runLabel(installed = false))
            assertTrue(p.id, '\n' !in p.title)
        }
    }
}
