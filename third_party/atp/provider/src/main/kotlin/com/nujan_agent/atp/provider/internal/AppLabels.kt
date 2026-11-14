// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.content.pm.PackageManager
import com.nujan_agent.atp.wire.AppText

/**
 * An app's name as the person sees it, without the hidden characters an app could put in its own label to change how
 * the text around it reads ([AppText.clean]); null when the app is gone, when this app cannot see it, or when nothing
 * visible is left. Package visibility shows another app only while it interacts with this one, such as binding its
 * service or starting its screens.
 */
internal fun appLabel(packages: PackageManager, packageName: String): String? = try {
    AppText.clean(packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()).ifBlank { null }
} catch (missing: PackageManager.NameNotFoundException) {
    null
}
