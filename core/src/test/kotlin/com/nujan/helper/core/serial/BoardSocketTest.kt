package com.nujan.helper.core.serial

import com.nujan.helper.core.runtime.RuntimeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BoardSocketTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the board's URL is rfc2217 to a host only the toolbox's route answers`() {
        assertEquals("rfc2217://nujan-helper-board:2217", BoardSocket.URL)
        assertEquals("/run/nujan-helper/board.sock", BoardSocket.GUEST_PATH)
        assertEquals("/f/run/board.sock", RuntimeLayout("/f").boardSocket)
    }

    @Test
    fun `the route sends the Helper's host to the socket and leaves every other address alone`() {
        val py = BoardSocket.PYTHON_SOURCE
        assertTrue(py.contains("HOST = \"nujan-helper-board\""))
        assertTrue(py.contains("PATH = \"/run/nujan-helper/board.sock\""))
        assertTrue(py.contains("socket.create_connection = create_connection"))
        assertTrue(py.contains("return _create_connection(address, timeout, source_address, **kwargs)"))
        assertTrue("pyserial's TCP_NODELAY is ignored", py.contains("if level == socket.IPPROTO_TCP:"))
        assertTrue("Python indents with spaces only", py.lines().none { it.startsWith("\t") })
    }

    @Test
    fun `the pth adds the route's directory and imports it only when it is there`() {
        val lines = BoardSocket.PTH.lines()
        assertEquals("/run/nujan-helper/python", lines[0])
        assertTrue(lines[1].startsWith("import "))
        assertTrue(lines[1].contains("find_spec(\"nujan_helper_board\") and __import__(\"nujan_helper_board\")"))
    }

    @Test
    fun `the route reaches every Python environment and the system Python, and nothing else`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "opt/embedded/lib/python3.11/site-packages").mkdirs()
        File(rootfs, "opt/node").mkdirs()
        File(rootfs, "usr/lib/python3.11").mkdirs()
        File(rootfs, "usr/lib/python3.11/os.py").writeText("")
        File(rootfs, "usr/lib/python3").mkdirs()
        val pythonDir = File(tmp.root, "run/python")

        BoardSocket.installRoute(pythonDir, rootfs)

        assertEquals(BoardSocket.PYTHON_SOURCE, File(pythonDir, "nujan_helper_board.py").readText())
        assertEquals(BoardSocket.PTH, File(rootfs, "opt/embedded/lib/python3.11/site-packages/nujan_helper_board.pth").readText())
        assertEquals(BoardSocket.PTH, File(rootfs, "usr/local/lib/python3.11/dist-packages/nujan_helper_board.pth").readText())
        assertFalse(File(rootfs, "usr/local/lib/python3").exists())
        assertEquals(2, rootfs.walkTopDown().count { it.name == "nujan_helper_board.pth" })
    }

    @Test
    fun `an environment made after the last launch gets the route at the next, and a changed route is rewritten`() {
        val rootfs = tmp.newFolder("rootfs")
        val pythonDir = File(tmp.root, "run/python")
        BoardSocket.installRoute(pythonDir, rootfs)
        File(rootfs, "opt/tools/lib/python3.13/site-packages").mkdirs()
        File(pythonDir, "nujan_helper_board.py").writeText("stale")

        BoardSocket.installRoute(pythonDir, rootfs)

        assertTrue(File(rootfs, "opt/tools/lib/python3.13/site-packages/nujan_helper_board.pth").isFile)
        assertEquals(BoardSocket.PYTHON_SOURCE, File(pythonDir, "nujan_helper_board.py").readText())
        assertTrue(pythonDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
