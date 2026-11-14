package com.nujan.helper.di

import android.content.Context
import com.nujan.helper.BuildConfig
import com.nujan.helper.core.runtime.JobEngine
import com.nujan.helper.mcp.CapabilityStore
import com.nujan.helper.mcp.HelperStatusTool
import com.nujan.helper.mcp.HelperToolCatalog
import com.nujan.helper.runtime.AndroidProcessRunner
import com.nujan.helper.runtime.BootstrapManager
import com.nujan.helper.runtime.DnsManager
import com.nujan.helper.runtime.ForegroundCoordinator
import com.nujan.helper.runtime.JobDiskLogs
import com.nujan.helper.runtime.PtyJobLauncher
import com.nujan.helper.runtime.PtyManager
import com.nujan.helper.runtime.RuntimeManager
import com.nujan.helper.runtime.RuntimeService
import com.nujan.helper.runtime.StorageManager
import com.nujan.helper.runtime.TerminalController
import com.nujan_agent.atp.provider.AtpProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Composition root — the single place that wires platform services to app code. The Helper uses a
 * ServiceLocator (not Hilt) because the app is single-process and single-activity; a DI framework
 * adds build time and ceremony for no gain.
 *
 * Holds the proot runtime and its managers, the job engine, and the tools every connected app is served; ATP's provider
 * kit keeps the grants and the open channels.
 */
object ServiceLocator {

    private lateinit var appContext: Context
    private var initialized = false

    /** The proot runtime (unpack + exec). Built lazily on first access after init(). */
    val runtimeManager: RuntimeManager by lazy {
        RuntimeManager(context(), AndroidProcessRunner())
    }

    /** DNS manager — owns rootfs/etc/resolv.conf and watches for network changes. */
    val dnsManager: DnsManager by lazy { DnsManager(context()) }

    /** The first-run bootstrap wizard (download + extract the Debian rootfs). */
    val bootstrapManager: BootstrapManager by lazy {
        BootstrapManager(context(), AndroidProcessRunner(), appScope, dnsManager, freeBytes = { storageManager.freeBytes() })
    }

    /** PTY sessions — proot supervisors on pseudo-terminals. */
    val ptyManager: PtyManager by lazy { PtyManager(context()) }

    /**
     * Storage breakdown and the "free up" actions for the dashboard. A job still queued or running keeps its log, and the
     * rootfs download stays while a setup runs or Linux is not installed yet.
     */
    val storageManager: StorageManager by lazy {
        StorageManager(
            context(),
            activeJobs = { jobEngine.list().filterNot { it.state.terminal }.mapTo(HashSet()) { it.id } },
            setupMayUseDownload = { bootstrapManager.running.value || !bootstrapManager.isBootstrapped() },
        )
    }

    /** A fresh interactive terminal session controller. One per open terminal screen. */
    fun newTerminalController(): TerminalController = TerminalController(ptyManager, appScope)

    /** Runs the foreground service while jobs are active or Linux is being set up, and stops the hosted MCP servers and
     *  any shared USB board once no app is connected. Started once from the Application. */
    val foregroundCoordinator: ForegroundCoordinator by lazy {
        ForegroundCoordinator(
            jobEngine.activeCount, bootstrapManager.settingUp, AtpProvider.connectedNow(context()), appScope,
            pairing = phonePairing.watching,
            startService = { active, settingUp -> RuntimeService.start(context(), active, settingUp) },
            stopService = { RuntimeService.stop(context()) },
            onNoAppConnected = {
                mcpServerSupervisor.stopAll()
                usbSerialBridge.close()
            },
        )
    }

    /** The warnings the person has accepted. Accepting the Helper's own lists the tools for connected apps. */
    val disclaimers: com.nujan.helper.phone.DisclaimerStore by lazy {
        com.nujan.helper.phone.DisclaimerStore(context()) { AtpProvider.toolsChanged(context()) }
    }

    /** This phone's Android shell, reached over Wireless debugging (`phone_*`). */
    val phoneLink: com.nujan.helper.phone.PhoneLink by lazy { com.nujan.helper.phone.PhoneLink(context()) }

    val phonePairing: com.nujan.helper.phone.PhonePairing by lazy { com.nujan.helper.phone.PhonePairing(context(), phoneLink, appScope) }

    val phoneHands: com.nujan.helper.phone.PhoneHands by lazy { com.nujan.helper.phone.PhoneHands(context()) }

    val phoneJournal: com.nujan.helper.phone.PhoneJournal by lazy { com.nujan.helper.phone.PhoneJournal(File(context().filesDir, "phone")) }

    /**
     * The sign that the phone is in use, with Stop for the turn it shows, and the switch that turns phone driving off,
     * which ends the connection at once, and every call running over it.
     */
    val phoneDriving: com.nujan.helper.phone.PhoneDriving by lazy {
        com.nujan.helper.phone.PhoneDriving(
            context(),
            appScope,
            onStop = { phoneLink.disconnect() },
            onSignDown = { appScope.launch { phoneHands.done() } },
            onTurnStopped = { app, turn -> AtpProvider.stopTurn(context(), app, turn) },
        )
    }

    /**
     * Asks Android, through the phone's shell, to let the Helper turn the Hands add-on on and off itself, so it is on only while an
     * app drives the phone; returns what stopped it, for the person, or null.
     */
    suspend fun allowHandsSwitching(): String? = runCatching {
        val answer = phoneLink.shell("pm grant ${context().packageName} android.permission.WRITE_SECURE_SETTINGS", 10_000L).outcome
        if (answer.exitCode == 0 && phoneHands.canSwitchItself()) null else answer.stderr.ifBlank { answer.stdout }.trim().ifEmpty { "Android did not allow it." }
    }.getOrElse { it.message ?: "The phone's shell did not answer." }

    /** Puts the Hands add-on carried in the Helper's assets on this phone; returns what stopped it, for the person. */
    suspend fun installHands(): String? = com.nujan.helper.phone.HandsInstall.install(context(), apkInstaller)

    /** The USB board shared with the toolbox (`usb_*`). */
    val usbSerialBridge: com.nujan.helper.usb.UsbSerialBridge by lazy { com.nujan.helper.usb.UsbSerialBridge(context(), appScope, runtimeManager.layout) }

    /** The Helper's own tools: status, the doctor, the workspace, command and install catalog, and USB, built once. The
     *  tools of the hosted MCP servers are added per request in [helperTools]. */
    private val baseTools: List<com.nujan.helper.core.mcp.McpTool> by lazy {
        buildList {
            add(
                HelperStatusTool(
                    version = BuildConfig.VERSION_NAME,
                    rootfsInstalled = { bootstrapManager.isBootstrapped() },
                    activeJobs = { jobEngine.activeCount.value },
                    usedBytes = { storageManager.usedBytes() ?: storageManager.report().totalBytes },
                    freeBytes = { storageManager.freeBytes() },
                    usb = { usbSerialBridge.toolboxNote() },
                    toolchains = {
                        com.nujan.helper.core.mcp.InstallProfiles.ids.filter { id ->
                            com.nujan.helper.core.mcp.InstallProfiles.get(id)?.isInstalledIn(runtimeManager.layout.rootfsDir) == true
                        }
                    },
                    termsAccepted = { disclaimers.accepted(com.nujan.helper.core.phone.Disclaimers.USING_THE_HELPER) },
                ),
            )
            add(
                com.nujan.helper.mcp.HelperDoctorTool(
                    rootfsInstalled = { bootstrapManager.isBootstrapped() },
                    freeBytes = { storageManager.freeBytes() },
                    runProbe = {
                        try {
                            val job = jobEngine.submit(
                                com.nujan.helper.core.runtime.JobSpec(
                                    command = listOf("sh", "-c", com.nujan.helper.core.runtime.HelperDoctor.PROBE),
                                    timeoutMs = com.nujan.helper.core.runtime.HelperDoctor.TIMEOUT_MS,
                                    label = "Checking the Linux toolbox",
                                    owner = com.nujan.helper.core.mcp.callingApp(),
                                ),
                            )
                            jobEngine.await(job.id)
                            Result.success(jobEngine.tail(job.id).orEmpty())
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    },
                ),
            )
            // The catalog: workspace + fs + command/job + dev-server tools.
            addAll(
                HelperToolCatalog(
                    layout = runtimeManager.layout,
                    jobEngine = jobEngine,
                    // install_apk: a PackageInstaller session; Android's confirmation gates it and its answer is reported.
                    installer = apkInstaller,
                    // export_workspace_zip: the FileProvider-mapped staging dir + the share-sheet opener.
                    exportsDir = { File(context().filesDir, "exports") },
                    shareFile = { file -> shareFileViaSystem(file) },
                    // Hosts curated MCP servers in the rootfs (singleton, holds live conns).
                    supervisor = mcpServerSupervisor,
                    usbNote = { usbSerialBridge.toolboxNote() ?: usbSerialBridge.nothingPluggedInHint() },
                    freeBytes = { storageManager.freeBytes() },
                ).tools(),
            )
            addAll(com.nujan.helper.mcp.UsbTools(context(), usbSerialBridge).tools())
            addAll(com.nujan.helper.mcp.PhoneTools(context(), phoneLink, phoneHands, phoneDriving, phoneJournal).tools())
        }
    }

    /** The Helper's tools as [caller]'s channels offer them: the fixed set and the hosted servers it runs now. */
    fun helperTools(caller: String?): List<com.nujan.helper.core.mcp.McpTool> = baseTools + mcpServerSupervisor.proxiedTools(caller)

    /** Hosts curated MCP servers inside the rootfs and holds their live stdio connections.
     *  A singleton so the live set survives the MCP tool-catalog rebuild; the app that runs a server hears
     *  when its tools come and go. */
    val mcpServerSupervisor: com.nujan.helper.mcp.McpServerSupervisor by lazy {
        com.nujan.helper.mcp.McpServerSupervisor(runtimeManager.layout, onToolsChanged = { owner -> AtpProvider.toolsChanged(context(), owner) })
    }

    /** Owner-toggleable capability grants enforced at the MCP boundary. */
    val capabilityStore: CapabilityStore by lazy { CapabilityStore(context()) { AtpProvider.toolsChanged(context()) } }

    /** Application-lifetime scope for background jobs (survives Activity recreation). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One-shot supervised jobs on the PTY seam, with disk + audit logs. */
    val jobEngine: JobEngine by lazy {
        val logs = JobDiskLogs(File(context().filesDir, "jobs"))
        JobEngine(
            launcher = PtyJobLauncher(ptyManager),
            scope = appScope,
            onLog = logs::append,
            onTerminal = logs::audit,
        )
    }

    /** Installs APKs through Android's package installer and reports each outcome (`install_apk`). */
    val apkInstaller: com.nujan.helper.install.ApkInstaller by lazy { com.nujan.helper.install.ApkInstaller(context()) }

    /**
     * Opens the Android share sheet for [file] (staged under the FileProvider-mapped `exports/`) through the
     * running call's way to show the Helper's screens, so it opens over the app the person is looking at; the answer
     * says whether it showed.
     */
    private suspend fun shareFileViaSystem(file: File): com.nujan_agent.atp.provider.AtpUiOutcome {
        val share = android.content.Intent(context(), com.nujan.helper.export.ShareExportActivity::class.java)
            .putExtra(com.nujan.helper.export.ShareExportActivity.EXTRA_FILE, file.name)
        return com.nujan.helper.atp.callUi().show(share, "Share the workspace zip")
    }

    /** Idempotent; called from [com.nujan.helper.HelperApplication.onCreate]. */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
    }

    fun context(): Context {
        check(initialized) { "ServiceLocator.init() must be called before use" }
        return appContext
    }
}
