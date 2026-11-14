package com.nujan.helper.core.serial

/**
 * Whether a toolbox command is looking for a board's serial port where the toolbox has none: under /dev or in sysfs,
 * with lsusb or pyserial's port list, or by leaving esptool, mpremote or miniterm to find a port. Programs there reach a
 * USB board only through the rfc2217:// URL usb_serial_open returns, so a command that names one is not looking.
 */
object SerialPortLookup {

    fun isLooking(command: String, output: String = ""): Boolean =
        !command.contains(PORT_URL, ignoreCase = true) && (SEARCHES.containsMatchIn(command) || NOT_FOUND.containsMatchIn(output))

    private const val PORT_URL = "rfc2217://"

    /**
     * Where a command looks for a serial port on a Linux machine, and the programs that look for one themselves. `/dev`
     * counts on its own, as in `ls /dev | grep tty`, but not as the start of /dev/null and its kind.
     */
    private val SEARCHES = Regex(
        """/dev/(tty|serial|bus/usb)|/dev(?=["'\s]|$)|tty.{0,2}(USB|ACM)|/sys/bus/usb|\blsusb\b|list_ports|\b(esptool|mpremote|miniterm)""",
        RegexOption.IGNORE_CASE,
    )

    /** What those programs print when they found no port. */
    private val NOT_FOUND = Regex(
        """/dev/tty(USB|ACM)|no serial ports? (were )?found|on any of the \d+ available serial ports|could not open port /dev/""",
        RegexOption.IGNORE_CASE,
    )
}
