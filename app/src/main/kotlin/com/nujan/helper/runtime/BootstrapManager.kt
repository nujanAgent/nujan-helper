package com.nujan.helper.runtime

import android.content.Context
import com.nujan.helper.core.runtime.BootstrapPlan
import com.nujan.helper.core.runtime.BootstrapState
import com.nujan.helper.core.runtime.BootstrapState.Stage
import com.nujan.helper.core.runtime.InstallRoom
import com.nujan.helper.core.runtime.ProcessRunner
import com.nujan.helper.core.runtime.ProotCommand
import com.nujan.helper.core.runtime.RootfsArtifact
import com.nujan.helper.core.runtime.RootfsSettings
import com.nujan.helper.core.runtime.RuntimeLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Drives the first-run setup as a single ordered [Flow] of [BootstrapState]: unpack proot → download the rootfs
 * (resumable) → verify SHA-256 → decompress and extract under proot → write DNS → smoke (`BOOTOK` + `aarch64`) →
 * basics, on a fresh rootfs. A stage's failure is emitted as [BootstrapState.Failed] rather than thrown, with a reason
 * the person can act on and what the step itself reported, so the UI can offer retry.
 *
 * Everything the app-owned process needs to turn a cold install into a bootable Debian userland
 * lives here, composed from the pure `:core` plan + the `:app` I/O helpers.
 *
 * [start] runs it in the app's [scope] rather than a screen's, so it carries on with the screen off or the screen
 * closed; [state] follows it, download and extraction progress included, for whichever screen is showing.
 */
class BootstrapManager(
    private val context: Context,
    private val runner: ProcessRunner,
    private val scope: CoroutineScope,
    // Shared with the app-lifetime DNS watcher so the rewrite count is unified.
    private val dns: DnsManager = DnsManager(context),
    /** The phone's free storage, which the setup and the basics must leave room in ([InstallRoom]). */
    private val freeBytes: () -> Long,
) {
    val layout = RuntimeLayout(context.filesDir.absolutePath)
    private val unpacker = AssetUnpacker(context)
    private val downloader = RootfsDownloader()
    private val extractor = RootfsExtractor()

    private val _state = MutableStateFlow<BootstrapState?>(null)
    /** The latest stage of the current or last run; null before the first. */
    val state: StateFlow<BootstrapState?> = _state.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _settingUp = MutableStateFlow(false)
    /** True while a run downloads, extracts or installs the basics: long work the foreground service keeps going. */
    val settingUp: StateFlow<Boolean> = _settingUp.asStateFlow()

    /** True if a prior bootstrap produced a rootfs with a shell and finished extracting it (cheap existence checks). */
    fun isBootstrapped(): Boolean = File(layout.rootfsShell).exists() && !File(layout.extractPending).exists()

    /** Runs the wizard in the app's scope unless a run is already going. */
    fun start() {
        if (!_running.compareAndSet(expect = false, update = true)) return
        _settingUp.value = !isBootstrapped() || File(layout.basicsPending).exists()
        scope.launch {
            try {
                run()
                    .catch { _state.value = BootstrapState.Failed(null, UNEXPECTED, it.message ?: it.javaClass.simpleName) }
                    .collect { _state.value = it }
            } finally {
                _settingUp.value = false
                _running.value = false
            }
        }
    }

    /** Run the wizard, emitting progress. Terminal state is [BootstrapState.Ready] or [Failed]. */
    fun run(): Flow<BootstrapState> = flow {
        layout.bootstrapDirs.forEach { File(it).mkdirs() }

        // proot itself must be unpacked before we can extract or smoke (AssetUnpacker).
        try {
            unpacker.unpack(layout)
        } catch (t: Throwable) {
            emit(BootstrapState.Failed(Stage.PREPARE, UNPACK_FAILED, t.message ?: t.javaClass.simpleName)); return@flow
        }

        // 1. Download (resumable) — skipped if a verified rootfs already exists.
        if (!isBootstrapped()) {
            // Checked before anything is fetched, so a phone without the room says so instead of filling up part-way.
            InstallRoom.refusal("Setting up Linux", BootstrapPlan.SETUP_BYTES, freeBytes(), SETUP_NEXT)?.let {
                emit(BootstrapState.Failed(Stage.PREPARE, it)); return@flow
            }
            emit(BootstrapState.Downloading(0, RootfsArtifact.COMPRESSED_BYTES))
            try {
                // Progress arrives on the download's own thread, so it goes to [state] directly rather than through the flow.
                downloader.download(layout, RootfsArtifact.SOURCES) { done, total ->
                    _state.value = BootstrapState.Downloading(done, total ?: RootfsArtifact.COMPRESSED_BYTES)
                }
            } catch (t: Throwable) {
                emit(BootstrapState.Failed(Stage.DOWNLOAD, DOWNLOAD_FAILED, t.message ?: t.javaClass.simpleName)); return@flow
            }

            // 2. Verify the pinned digest before we trust the bytes.
            emit(BootstrapState.Verifying)
            val actual = sha256(File(layout.rootfsArchive))
            if (!actual.equals(RootfsArtifact.SHA256, ignoreCase = true)) {
                File(layout.rootfsArchive).delete() // corrupt/tampered — force a clean re-download.
                emit(BootstrapState.Failed(Stage.VERIFY, DOWNLOAD_DAMAGED, "sha256 mismatch: got $actual"))
                return@flow
            }

            // 3. Decompress + extract under proot.
            emit(BootstrapState.Extracting(0))
            File(layout.extractPending).createNewFile()
            val ex = extractor.extract(
                layout,
                RootfsArtifact.COMPRESSION,
                progress = { bytes -> _state.value = BootstrapState.Extracting(bytes) },
            )
            if (!ex.ok) {
                emit(BootstrapState.Failed(Stage.EXTRACT, if (ex.timedOut) UNPACKING_SLOW else UNPACKING_STOPPED, extractReason(ex)))
                return@flow
            }
            File(layout.rootfsArchive).delete() // reclaim the tarball once extracted.
            val hosts = File(layout.rootfsDir, "etc/hosts")
            if (!hosts.exists()) hosts.writeText(BootstrapPlan.HOSTS)
            RootfsSettings.apply(File(layout.rootfsDir))
            // Marked before the basics run, so a bootstrap stopped part-way installs them on its next run.
            File(layout.basicsPending).createNewFile()
            File(layout.extractPending).delete()
        }

        // 4. DNS.
        emit(BootstrapState.ConfiguringNetwork)
        try {
            dns.writeResolvConf(layout)
        } catch (t: Throwable) {
            emit(BootstrapState.Failed(Stage.NETWORK, NETWORK_FAILED, t.message ?: t.javaClass.simpleName)); return@flow
        }

        // 5. Smoke: proot -r rootfs sh -c 'echo BOOTOK && uname -m'.
        emit(BootstrapState.SmokeTesting)
        val spec = BootstrapPlan.smoke(layout)
        val res = runner.run(ProotCommand.argv(spec), ProotCommand.env(spec), timeoutMs = 60_000)
        val out = res.stdout
        when {
            out.contains("BOOTOK") && out.contains("aarch64") -> Unit
            res.timedOut -> { emit(BootstrapState.Failed(Stage.SMOKE, LINUX_SLOW, "proot timed out")); return@flow }
            else -> {
                val reported = "exit=${res.exitCode} ${res.stderr.take(200).ifBlank { out.take(200) }}"
                emit(BootstrapState.Failed(Stage.SMOKE, LINUX_DID_NOT_START, reported))
                return@flow
            }
        }

        // 6. A fresh rootfs installs the basics; a rootfs bootstrapped earlier keeps what it has.
        val pending = File(layout.basicsPending)
        if (!pending.exists()) {
            emit(BootstrapState.Ready("aarch64")); return@flow
        }
        // Without the room, the basics stay marked for a later run; the toolbox works without them.
        InstallRoom.refusal("Installing the basics", BootstrapPlan.BASICS_BYTES, freeBytes(), BASICS_NEXT)?.let {
            emit(BootstrapState.Ready("aarch64", basicsNote = "${BootstrapPlan.BASICS.joinToString()} are not installed yet ($it)"))
            return@flow
        }
        emit(BootstrapState.InstallingBasics)
        val basics = BootstrapPlan.basics(layout)
        val installed = runner.run(ProotCommand.argv(basics), ProotCommand.env(basics), timeoutMs = 15 * 60_000)
        if (installed.exitCode == 0 && !installed.timedOut) {
            pending.delete()
            emit(BootstrapState.Ready("aarch64"))
        } else {
            val why = if (installed.timedOut) "apt timed out" else installed.stderr.lines().lastOrNull { it.isNotBlank() }
            emit(BootstrapState.Ready("aarch64", basicsNote = "${BootstrapPlan.BASICS.joinToString()} are not installed yet ($why)"))
        }
    }

    private fun extractReason(ex: RootfsExtractor.Result): String =
        if (ex.timedOut) "extract timed out" else "exit=${ex.exitCode} ${ex.stderr.take(200)}"

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(256 * 1024)
            var n = input.read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = input.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What a setup refused for want of room tells the person to do. */
private const val SETUP_NEXT = "Free some space on the phone, then tap Install again."

// Why a setup stopped, as the person reads it after where it stopped; what the step itself reported is kept apart.
private const val UNEXPECTED = "Something unexpected went wrong."
private const val UNPACK_FAILED = "The program that runs Linux could not be unpacked from the Helper."
private const val DOWNLOAD_FAILED = "The download did not finish. Check the phone's connection, then tap Install again."
private const val DOWNLOAD_DAMAGED = "The download was damaged, so it was deleted. Tap Install to download it again."
private const val UNPACKING_SLOW = "Unpacking took too long. Tap Install to try again."
private const val UNPACKING_STOPPED = "Unpacking stopped part-way. Tap Install to try again."
private const val NETWORK_FAILED = "Linux's network settings could not be written. The Helper tries again when it next opens."
private const val LINUX_SLOW = "Linux did not answer in time. The Helper tries again when it next opens."
private const val LINUX_DID_NOT_START = "Linux did not start. The Helper tries again when it next opens."

/** What the basics, left for want of room, tell the person. */
private const val BASICS_NEXT = "Free some space on the phone; the Helper installs them when it next opens."
