package com.nujan.helper.atp

import android.content.pm.PackageManager
import com.nujan.helper.di.ServiceLocator
import com.nujan_agent.atp.wire.AppText

/**
 * The Helper's part when a connected app goes (SPEC.md 7.4): the provider kit closes its channels, and what the app
 * started in the Helper ends with it.
 */
object ConnectedApps {

    /** Stops the jobs and hosted servers [packageName] started, which nothing can reach once it is disconnected. */
    fun endWork(packageName: String) {
        ServiceLocator.jobEngine.cancelOwnedBy(packageName)
        ServiceLocator.mcpServerSupervisor.stopOwnedBy(packageName)
    }

    /**
     * The app's name as the launcher shows it, without the hidden characters an app could put in its own label
     * ([AppText.clean]), or its package when it is no longer installed or nothing visible is left.
     */
    fun label(packageManager: PackageManager, packageName: String): String = try {
        AppText.clean(packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString())
            .ifBlank { packageName }
    } catch (missing: PackageManager.NameNotFoundException) {
        packageName
    }
}
