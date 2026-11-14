package com.nujan.helper.core.serial

import com.nujan.helper.core.runtime.writeIfChanged
import java.io.File

/**
 * Where a shared board is served: a Unix socket in the Helper's private storage, bound into the toolbox at [GUEST_DIR].
 * Only processes running as the Helper, its toolbox among them, can open it; a loopback port would be open to every app
 * on the phone.
 *
 * Programs open the board as [URL]. pyserial, which esptool, mpremote and miniterm use, reaches an rfc2217:// URL through
 * `socket.create_connection`, and [PYTHON_SOURCE], loaded in every Python environment of the toolbox by [PTH], answers
 * that call for [HOST] with the socket.
 */
object BoardSocket {
    const val HOST = "nujan-helper-board"
    const val URL = "rfc2217://$HOST:2217"
    const val GUEST_DIR = "/run/nujan-helper"
    const val FILE_NAME = "board.sock"
    const val GUEST_PATH = "$GUEST_DIR/$FILE_NAME"

    const val PYTHON_MODULE = "nujan_helper_board"
    const val PYTHON_DIR = "$GUEST_DIR/python"

    /** A `.pth` file for a Python site directory: puts [PYTHON_DIR] on the path and loads the route when it is there. */
    const val PTH = "$PYTHON_DIR\nimport importlib.util as u; u.find_spec(\"$PYTHON_MODULE\") and __import__(\"$PYTHON_MODULE\")\n"

    val PYTHON_SOURCE = """
        |${"\"\"\""}Opens the board the Helper shares, $URL, on its Unix socket at $GUEST_PATH.
        |
        |pyserial connects rfc2217:// URLs with socket.create_connection; this answers that call for the Helper's host name
        |and passes every other one on unchanged.
        |${"\"\"\""}
        |import socket
        |
        |HOST = "$HOST"
        |PATH = "$GUEST_PATH"
        |
        |
        |class _BoardSocket(socket.socket):
        |    # pyserial turns off Nagle's delay with a TCP option, which a Unix socket neither has nor needs.
        |    def setsockopt(self, level, option, value, *rest):
        |        if level == socket.IPPROTO_TCP:
        |            return None
        |        return super().setsockopt(level, option, value, *rest)
        |
        |
        |_create_connection = socket.create_connection
        |
        |
        |def create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, **kwargs):
        |    if not address or address[0] != HOST:
        |        return _create_connection(address, timeout, source_address, **kwargs)
        |    s = _BoardSocket(socket.AF_UNIX, socket.SOCK_STREAM)
        |    try:
        |        if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
        |            s.settimeout(timeout)
        |        s.connect(PATH)
        |    except OSError as e:
        |        s.close()
        |        raise OSError(e.errno, "no board is shared (usb_serial_open shares one): {}".format(e.strerror)) from None
        |    return s
        |
        |
        |socket.create_connection = create_connection
        |""".trimMargin()

    /**
     * Puts the route where every Python in the rootfs at [rootfsDir] finds it: [PYTHON_SOURCE] in [pythonDir], the host
     * directory bound at [PYTHON_DIR], and [PTH] in each [siteDirs] entry. Writes only what differs, so it is cheap to run
     * before every launch, and an environment created since the last launch gets the route at the next one.
     */
    fun installRoute(pythonDir: File, rootfsDir: File) {
        writeIfChanged(File(pythonDir, "$PYTHON_MODULE.py"), PYTHON_SOURCE)
        siteDirs(rootfsDir).forEach { writeIfChanged(File(it, "$PYTHON_MODULE.pth"), PTH) }
    }

    /**
     * The Python site directories in a rootfs: each environment's under /opt, such as the embedded profile's, and the
     * system Python's /usr/local one for each Python 3 the rootfs has.
     */
    fun siteDirs(rootfsDir: File): List<File> {
        val environments = File(rootfsDir, "opt").listFiles().orEmpty()
            .flatMap { File(it, "lib").listFiles().orEmpty().asList() }
            .filter { PYTHON_VERSION.matches(it.name) }
            .map { File(it, "site-packages") }
            .filter { it.isDirectory }
        val system = File(rootfsDir, "usr/lib").listFiles().orEmpty()
            .filter { PYTHON_VERSION.matches(it.name) && File(it, "os.py").isFile }
            .map { File(rootfsDir, "usr/local/lib/${it.name}/dist-packages") }
        return (environments + system).sortedBy { it.path }
    }

    private val PYTHON_VERSION = Regex("python3\\.\\d+")
}
