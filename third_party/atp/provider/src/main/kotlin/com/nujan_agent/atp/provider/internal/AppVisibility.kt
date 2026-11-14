// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether one of the app's activities is started in this process, so it is on screen and may start activities itself.
 * Watched from the kit's content provider, which Android creates before the Application and before any activity.
 */
internal object AppVisibility {

    private val started = AtomicInteger()
    private val watching = AtomicBoolean()

    val onScreen: Boolean get() = started.get() > 0

    fun watch(application: Application) {
        if (!watching.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                started.updateAndGet { (it - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
