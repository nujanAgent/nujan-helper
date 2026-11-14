// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.v1;

import com.nujan_agent.atp.v1.IAtpHost;

/**
 * The service a provider exports for com.nujan_agent.atp.action.PROVIDER (SPEC.md section 4).
 * Every call is checked against Binder.getCallingUid(); errors come back as status JSON, never thrown.
 */
interface IAtpProvider {
    /** The caller's own grant state, the static servers, the limits and the binding version, and a consent nonce on request. */
    byte[] hello(in byte[] helloJson);

    /** Opens the channel for (caller uid, serverId) on two pipes the host created; returns status JSON. */
    byte[] openChannel(String serverId, in ParcelFileDescriptor toProvider,
                       in ParcelFileDescriptor fromProvider, IAtpHost host, in byte[] openJson);

    /** Closes the caller's channel for serverId with a close reason. */
    oneway void closeChannel(String serverId, int reason);

    /** The result of a UI handoff the provider asked for with IAtpHost.onUiRequest. */
    oneway void uiResult(String serverId, int requestId, int resultCode);

    /** Deletes the caller's grant: the host's Disconnect. */
    void releaseGrant();
}
