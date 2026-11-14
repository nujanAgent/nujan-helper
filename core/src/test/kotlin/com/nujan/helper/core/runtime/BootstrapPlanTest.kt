package com.nujan.helper.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsArtifactTest {
    @Test
    fun `pinned digest is a 64-char lowercase hex sha-256`() {
        assertEquals(64, RootfsArtifact.SHA256.length)
        assertTrue(RootfsArtifact.SHA256.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the layer comes from Docker Hub by its digest first, then from the commit Debian's builders pushed`() {
        val hub = RootfsArtifact.SOURCES.first() as RootfsSource.DockerHubBlob
        assertEquals("sha256:${RootfsArtifact.SHA256}", hub.digest)
        assertEquals(
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull",
            hub.tokenUrl,
        )
        assertEquals("https://registry-1.docker.io/v2/library/debian/blobs/sha256:${RootfsArtifact.SHA256}", hub.blobUrl)
        val git = RootfsArtifact.SOURCES[1] as RootfsSource.Url
        assertTrue(git.url.startsWith("https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/"))
        assertTrue("a commit, never a branch", Regex("/[0-9a-f]{40}/trixie/").containsMatchIn(git.url))
        assertEquals(Compression.GZIP, RootfsArtifact.COMPRESSION)
    }

    @Test
    fun `the pull token is read from the auth service's answer, and nothing else is taken for one`() {
        assertEquals("abc", RootfsSource.DockerHubBlob.tokenFrom("""{"token":"abc","expires_in":300}"""))
        assertEquals("xyz", RootfsSource.DockerHubBlob.tokenFrom("""{"access_token":"xyz"}"""))
        assertEquals(null, RootfsSource.DockerHubBlob.tokenFrom("""{"token":""}"""))
        assertEquals(null, RootfsSource.DockerHubBlob.tokenFrom("<html>rate limited</html>"))
        assertEquals(null, RootfsSource.DockerHubBlob.tokenFrom("[]"))
    }
}

class ResolvConfTest {
    @Test
    fun `renders device resolvers, one nameserver line each`() {
        val text = ResolvConf.render(listOf("192.168.1.1", "8.8.4.4"))
        assertTrue(text.contains("nameserver 192.168.1.1\n"))
        assertTrue(text.contains("nameserver 8.8.4.4\n"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `blank and duplicate resolvers are dropped`() {
        val text = ResolvConf.render(listOf(" 1.1.1.1 ", "", "1.1.1.1"))
        assertEquals(1, text.lines().count { it.startsWith("nameserver ") })
        assertTrue(text.contains("nameserver 1.1.1.1"))
    }

    @Test
    fun `empty input falls back so DNS is never empty`() {
        val text = ResolvConf.render(emptyList())
        ResolvConf.FALLBACK.forEach { assertTrue(text.contains("nameserver $it")) }
    }
}

class BootstrapPlanTest {
    private val layout = RuntimeLayout("/f")

    @Test
    fun `extract runs host-fs toybox tar under root emulation and link2symlink, reading stdin`() {
        val argv = ProotCommand.argv(BootstrapPlan.extract(layout))
        assertEquals(
            listOf(
                "/f/bootstrap/bin/proot", "-0", "--link2symlink",
                "/system/bin/toybox", "tar", "x", "-C", "/f/rootfs", "-f", "-",
            ),
            argv,
        )
        // Host-fs mode: never a `-r` (no rootfs to chroot into while we are still creating it).
        assertFalse(argv.contains("-r"))
    }

    @Test
    fun `a fresh rootfs gets localhost in hosts and its basics installed the way a job runs`() {
        assertTrue(BootstrapPlan.HOSTS.lines().first().matches(Regex("127\\.0\\.0\\.1\\s+localhost")))
        val argv = ProotCommand.argv(BootstrapPlan.basics(layout))
        assertEquals(
            listOf("/f/bootstrap/bin/proot", "-0", "--link2symlink", "--kill-on-exit", "-r", "/f/rootfs"),
            argv.take(6),
        )
        val script = argv.last()
        assertTrue(script, script.startsWith("DEBIAN_FRONTEND=noninteractive dpkg --configure -a; "))
        assertTrue(script.contains("apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "))
        assertTrue(script.split(" ").containsAll(listOf("ca-certificates", "curl", "netbase", "procps", "python3")))
        assertEquals("/f/downloads/basics-pending", layout.basicsPending)
    }

    /**
     * The setup starts only with room for the most it holds at once: the unpacked rootfs beside the download until it
     * is unpacked, then beside the basics.
     */
    @Test
    fun `the setup asks for the room it holds at its fullest`() {
        assertTrue(RootfsArtifact.EXTRACTED_BYTES >= RootfsArtifact.UNCOMPRESSED_BYTES)
        assertTrue(BootstrapPlan.SETUP_BYTES >= RootfsArtifact.EXTRACTED_BYTES + RootfsArtifact.COMPRESSED_BYTES)
        assertTrue(BootstrapPlan.SETUP_BYTES >= RootfsArtifact.EXTRACTED_BYTES + BootstrapPlan.BASICS_BYTES)
        val refusal = InstallRoom.refusal("Setting up Linux", BootstrapPlan.SETUP_BYTES, InstallRoom.RESERVE_BYTES)
        assertTrue(refusal, refusal!!.startsWith("Setting up Linux needs about ${Storage.humanBytes(BootstrapPlan.SETUP_BYTES)}"))
    }

    @Test
    fun `smoke chroots into the rootfs with proc-dev-sys bound`() {
        val argv = ProotCommand.argv(BootstrapPlan.smoke(layout))
        assertEquals(
            listOf(
                "/f/bootstrap/bin/proot", "-0", "-r", "/f/rootfs",
                "-b", "/proc", "-b", "/dev", "-b", "/sys", "-w", "/root",
                "/bin/sh", "-c", "export PATH=/usr/sbin:/usr/bin:/sbin:/bin; echo BOOTOK && uname -m",
            ),
            argv,
        )
    }
}
