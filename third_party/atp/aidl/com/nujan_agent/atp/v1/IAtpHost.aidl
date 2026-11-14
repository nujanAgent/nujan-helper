// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.v1;

import android.app.PendingIntent;

/**
 * The callback a host passes to IAtpProvider.openChannel (SPEC.md section 4). All calls are oneway, so a
 * provider never blocks on a host; the host checks the calling uid on every one.
 */
interface IAtpHost {
    /** The provider asks the host to show its activity, during an in-flight call from this host. */
    oneway void onUiRequest(int requestId, in PendingIntent intent, in byte[] reasonJson);

    /** The channel closed; reason is a close-reason value and detailJson says why. */
    oneway void onClosed(int reason, in byte[] detailJson);

    /** The provider's static servers changed; discover again. */
    oneway void onServersChanged();
}
