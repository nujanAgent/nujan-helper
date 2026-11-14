package com.nujan.helper

import android.app.Application
import android.os.Build
import android.util.Log
import com.nujan.helper.atp.ConnectedApps
import com.nujan.helper.atp.HelperPins
import com.nujan.helper.di.ServiceLocator
import com.nujan.helper.runtime.PtyNative
import com.nujan_agent.atp.provider.AtpProvider
import java.io.File

class HelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything starts a process, so every process inherits it.
        Log.i(TAG, "close_range guard: ${PtyNative.hideCloseRange()}")
        ServiceLocator.init(this)
        // ATP's provider kit: the hosts the Helper trusts, what a new connection starts with, and the work that ends
        // when an app disconnects.
        HelperPins.configure()
        AtpProvider.onDisconnected = ConnectedApps::endWork
        // The ATP consent screen runs in a process of its own, which must not start a second set of watchers and
        // services beside the main process's.
        if (!isMainProcess()) return
        // Keep rootfs DNS fresh across network changes for the app's lifetime. The initial
        // callback also (re)writes resolv.conf once the rootfs exists.
        ServiceLocator.dnsManager.startWatching(ServiceLocator.runtimeManager.layout)
        // Run the foreground service whenever a job is active, so work survives backgrounding.
        ServiceLocator.foregroundCoordinator.start()
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(packageName)
        }
        return name == packageName
    }

    private companion object {
        const val TAG = "NujanHelper"
    }
}
