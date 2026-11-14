package com.nujan.helper.ui

import com.nujan.helper.core.runtime.BootstrapState
import com.nujan.helper.core.runtime.Job
import com.nujan.helper.core.runtime.JobState
import com.nujan.helper.core.runtime.RootfsArtifact
import com.nujan.helper.core.runtime.Storage
import com.nujan.helper.install.installQuestion
import com.nujan.helper.usb.UsbSerialBridge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Helper's screens say about a state: the toolbox before and while it installs, where and why it stopped, and
 * when it is checked, USB with its switch off or a board shared, how a command ended, and what a job's card is headed by.
 */
class StateLinesTest {

    private val board = UsbSerialBridge.Board(
        name = "/dev/bus/usb/001/002",
        vendorId = 0x303A,
        productId = 0x1001,
        product = "USB JTAG/serial debug unit",
        chip = "ESP32-S3",
        hasSerialDriver = true,
        allowed = true,
    )

    private fun job(state: JobState, exit: Int? = null, error: String? = null) =
        Job(id = "j1", workspaceId = null, argv = listOf("sh", "-c", "true"), state = state, exitCode = exit, error = error)

    @Test
    fun `an installed toolbox being checked reads as routine, not a warning`() {
        assertEquals(StateLine("Installed · checking it runs"), toolboxState(BootstrapState.SmokeTesting, running = true, installed = true))
        assertEquals(StateLine("Installed · checking it runs"), toolboxState(BootstrapState.ConfiguringNetwork, running = true, installed = true))
    }

    @Test
    fun `a toolbox checked before it is installed is still a warning`() {
        assertEquals(Tone.WARN, toolboxState(BootstrapState.SmokeTesting, running = true, installed = false).tone)
    }

    /** A stopped install says where in words and why; what the step itself reported stays out of the line. */
    @Test
    fun `an install that stopped says where and why in words, for every stage`() {
        val words = mapOf(
            BootstrapState.Stage.PREPARE to "while getting ready",
            BootstrapState.Stage.DOWNLOAD to "while downloading",
            BootstrapState.Stage.VERIFY to "while checking the download",
            BootstrapState.Stage.EXTRACT to "while unpacking",
            BootstrapState.Stage.NETWORK to "while setting up the network",
            BootstrapState.Stage.SMOKE to "while testing it runs",
        )
        BootstrapState.Stage.entries.forEach { stage ->
            val line = toolboxState(BootstrapState.Failed(stage, "It failed.", detail = "exit=1 E: raw"), running = false, installed = false)
            assertEquals(StateLine("Install stopped ${words.getValue(stage)}. It failed.", Tone.ERROR), line)
        }
    }

    @Test
    fun `a check of an installed toolbox that stopped reads as a check, and an error outside the stages names none`() {
        assertEquals(
            StateLine("Check stopped while testing it runs. Linux did not start.", Tone.ERROR),
            toolboxState(BootstrapState.Failed(BootstrapState.Stage.SMOKE, "Linux did not start."), running = false, installed = true),
        )
        assertEquals(
            StateLine("Install stopped. Something unexpected went wrong.", Tone.ERROR),
            toolboxState(BootstrapState.Failed(null, "Something unexpected went wrong.", "boom"), running = false, installed = false),
        )
    }

    @Test
    fun `the architecture reads as a person says it, from Android's name or Linux's`() {
        assertEquals("64-bit ARM", archName("arm64-v8a"))
        assertEquals("64-bit ARM", archName("aarch64"))
        assertEquals("32-bit ARM", archName("armeabi-v7a"))
        assertEquals("riscv64", archName("riscv64"))
        assertEquals(StateLine("Installed · runs on 64-bit ARM", Tone.OK), toolboxState(BootstrapState.Ready("aarch64"), running = false, installed = true))
    }

    @Test
    fun `a toolbox not installed yet names the size its download then counts up to`() {
        val pinned = RootfsArtifact.COMPRESSED_BYTES
        val size = Storage.humanBytes(pinned)
        assertEquals(StateLine("Not installed · $size to download"), toolboxState(BootstrapState.Idle, running = false, installed = false))
        assertEquals(StateLine("Not installed · $size to download"), toolboxState(null, running = false, installed = false))
        assertEquals(
            StateLine("Downloading · $size of $size", Tone.WARN),
            toolboxState(BootstrapState.Downloading(pinned, pinned), running = true, installed = false),
        )
    }

    @Test
    fun `USB switched off says where to turn it on, whatever is plugged in`() {
        assertEquals(StateLine("Switched off in Rules", Tone.WARN), usbState(listOf(board), shared = null, enabled = false))
    }

    @Test
    fun `a shared board is named by its chip, with its baud rate`() {
        assertEquals(
            StateLine("ESP32-S3 is shared with programs, at 115200 baud", Tone.OK),
            usbState(listOf(board), UsbSerialBridge.Shared(board, 115200), enabled = true),
        )
    }

    @Test
    fun `a command that exits non-zero finished with its code, as a warning`() {
        assertEquals(StateLine("Finished", Tone.OK), jobState(job(JobState.DONE, exit = 0)))
        assertEquals(StateLine("Finished with exit code 2", Tone.WARN), jobState(job(JobState.DONE, exit = 2)))
    }

    @Test
    fun `a command that could not run says why`() {
        assertEquals(StateLine("Failed: no such file", Tone.ERROR), jobState(job(JobState.FAILED, error = "no such file")))
        assertEquals(StateLine("Failed, exit code 127", Tone.ERROR), jobState(job(JobState.FAILED, exit = 127)))
    }

    @Test
    fun `a shell command is shown as it was written`() {
        assertEquals("uname -m && echo ok", commandText(listOf("sh", "-c", "uname -m && echo ok")))
        assertEquals("python3 app.py", commandText(listOf("python3", "app.py")))
    }

    /** An APK names its own app, so the question sets that name apart, without hidden characters that could reorder it. */
    @Test
    fun `the install question sets the app's own name apart`() {
        assertEquals("Install \u2068Lisbon Maps\u2069?", installQuestion("Lisbon \u202EMaps"))
    }

    /** The Helper's own work is headed by what it does, never by its script; an app's command reads as written. */
    @Test
    fun `a job is headed by its label when the Helper named it, otherwise by its command`() {
        val install = job(JobState.RUNNING).copy(argv = listOf("sh", "-c", "set -e\nexport DEBIAN_FRONTEND=noninteractive\napt-get update"), label = "Installing Python tools")
        assertEquals("Installing Python tools", jobHeading(install))
        assertEquals("true", jobHeading(job(JobState.DONE, exit = 0)))
    }
}
