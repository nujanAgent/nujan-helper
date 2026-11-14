package com.nujan.helper.core.serial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A command that looks for a board under /dev, or leaves a flashing tool to find one, is told where the board is. */
class SerialPortLookupTest {

    @Test
    fun `looking under dev, in sysfs, or with lsusb is looking for a port`() {
        listOf(
            "ls -la /dev/ttyUSB* /dev/ttyACM* 2>&1; which python3 pip3 esptool",
            "ls -la /dev/bus/usb/001/ 2>&1 | head",
            "ls /dev | grep -i -E \"tty(USB|ACM)\"",
            "ls /dev | grep -i -E \"tty|usb\" 2>&1 | head -20",
            "find /dev -name 'tty*'",
            "ls /sys/bus/usb-serial/devices 2>&1; cat /proc/tty/drivers",
            "lsusb 2>&1 | head -3",
            "python3 -m serial.tools.list_ports -v",
            "dmesg | grep -i ttyUSB",
        ).forEach { assertTrue(it, SerialPortLookup.isLooking(it)) }
    }

    @Test
    fun `a flashing tool left to find its own port is looking for one`() {
        assertTrue(SerialPortLookup.isLooking("esptool chip-id"))
        assertTrue(SerialPortLookup.isLooking("mpremote ls"))
        assertTrue(SerialPortLookup.isLooking("python3 -m pip install --break-system-packages esptool"))
    }

    @Test
    fun `a command that names the shared port is not looking`() {
        assertFalse(SerialPortLookup.isLooking("esptool --port rfc2217://127.0.0.1:41234 chip-id"))
        assertFalse(SerialPortLookup.isLooking("mpremote connect rfc2217://127.0.0.1:41234 ls", output = "Serial port rfc2217://127.0.0.1:41234"))
    }

    @Test
    fun `what a program prints when it found no port counts as looking`() {
        assertTrue(
            SerialPortLookup.isLooking(
                "python3 flash.py",
                output = "A fatal error occurred: Could not connect to an Espressif device on any of the 0 available serial ports.",
            ),
        )
        assertTrue(SerialPortLookup.isLooking("python3 read.py", output = "could not open port /dev/ttyUSB0: No such file or directory"))
        assertTrue(SerialPortLookup.isLooking("./probe.sh", output = "No serial ports found"))
    }

    @Test
    fun `ordinary commands are not looking`() {
        listOf(
            "uname -a", "cat main.py", "pip install flask", "python3 -c 'print(1)'", "ls -la",
            "make 2>/dev/null", "head -c 16 /dev/urandom | xxd", "apt-get update >/dev/null 2>&1",
        ).forEach { assertFalse(it, SerialPortLookup.isLooking(it, output = "done")) }
    }
}
