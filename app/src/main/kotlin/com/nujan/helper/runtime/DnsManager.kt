package com.nujan.helper.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import com.nujan.helper.core.runtime.ResolvConf
import com.nujan.helper.core.runtime.RuntimeLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Owns the rootfs `/etc/resolv.conf`. Android has no `/etc/resolv.conf`, and a rootfs image's own is
 * missing or a dangling systemd-resolved symlink, so the Helper writes a real file from
 * the device's live resolvers — and **keeps it fresh**: [startWatching] registers a default-network
 * callback so a Wi-Fi↔cellular switch (which changes the resolvers) re-writes the file. Without this,
 * a rootfs booted on Wi-Fi would keep stale nameservers after the phone moved to cellular and DNS
 * would silently break inside the guest.
 */
class DnsManager(private val context: Context) {

    data class Status(val resolvers: List<String>, val rewrites: Int, val watching: Boolean)

    private val _status = MutableStateFlow(Status(emptyList(), 0, watching = false))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val cm: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var watchLayout: RuntimeLayout? = null

    /** Current system resolvers, best-effort. Empty if the platform won't tell us (→ fallback). */
    fun deviceResolvers(): List<String> {
        val mgr = cm ?: return emptyList()
        val network = mgr.activeNetwork ?: return emptyList()
        val props = mgr.getLinkProperties(network) ?: return emptyList()
        return props.dnsServers.mapNotNull { it.hostAddress }
    }

    /**
     * Write `rootfs/etc/resolv.conf` from the live resolvers (falling back to public DNS if the
     * device yields none — see [ResolvConf]). Replaces the dangling symlink the image ships.
     */
    fun writeResolvConf(layout: RuntimeLayout) {
        val path = Paths.get(layout.rootfsResolvConf)
        File(layout.rootfsResolvConf).parentFile?.mkdirs()
        // An image may ship resolv.conf as a DANGLING symlink (→ /run/systemd/resolve/...). A plain
        // File.writeText would follow it to a missing target dir → ENOENT.
        // NIO's deleteIfExists removes the link itself without following it; then we write a
        // real file. (Files.delete/write operate on the link, not its target, by default.)
        val resolvers = deviceResolvers()
        Files.deleteIfExists(path)
        Files.write(path, ResolvConf.render(resolvers).toByteArray())
        _status.update {
            it.copy(resolvers = resolvers.ifEmpty { ResolvConf.FALLBACK }, rewrites = it.rewrites + 1)
        }
    }

    /**
     * Start reacting to network changes. Registers a default-network callback that re-writes
     * resolv.conf whenever the active network or its link properties change. Idempotent. The initial
     * callback fires for the current network, so this also does the first write once the rootfs exists.
     * [Status.watching] turns true only once Android has taken the callback.
     */
    @Synchronized
    fun startWatching(layout: RuntimeLayout) {
        if (callback != null) return
        watchLayout = layout
        val mgr = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh()
            override fun onLost(network: Network) = refresh()
        }
        try {
            mgr.registerDefaultNetworkCallback(cb)
        } catch (refused: RuntimeException) {
            // Android refuses past its limit of callbacks per app. resolv.conf is still written at setup and each time
            // the Helper opens, but not when the network changes, and the toolbox says so.
            Log.w(TAG, "Network changes are not watched; resolv.conf is written only when the Helper opens", refused)
            return
        }
        callback = cb
        _status.update { it.copy(watching = true) }
    }

    @Synchronized
    fun stopWatching() {
        val cb = callback ?: return
        runCatching { cm?.unregisterNetworkCallback(cb) }
        callback = null
        _status.update { it.copy(watching = false) }
    }

    /** React to a network event: rewrite if the rootfs exists, else just track the resolvers. */
    private fun refresh() {
        val layout = watchLayout ?: return
        if (File(layout.rootfsShell).exists()) {
            runCatching { writeResolvConf(layout) }
        } else {
            _status.update { it.copy(resolvers = deviceResolvers()) }
        }
    }

    private companion object {
        const val TAG = "NujanHelper"
    }
}
