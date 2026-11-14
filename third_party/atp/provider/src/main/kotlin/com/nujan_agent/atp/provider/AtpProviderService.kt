// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nujan_agent.atp.provider.internal.AppVisibility
import com.nujan_agent.atp.provider.internal.CallAsk
import com.nujan_agent.atp.provider.internal.ChannelServer
import com.nujan_agent.atp.provider.internal.ChannelUi
import com.nujan_agent.atp.provider.internal.Channels
import com.nujan_agent.atp.provider.internal.Engine
import com.nujan_agent.atp.provider.internal.OpenDecision
import com.nujan_agent.atp.provider.internal.SigningCertificates
import com.nujan_agent.atp.provider.internal.ToolDispatcher
import com.nujan_agent.atp.provider.internal.appLabel
import com.nujan_agent.atp.provider.internal.toolTitle
import com.nujan_agent.atp.v1.IAtpHost
import com.nujan_agent.atp.v1.IAtpProvider
import com.nujan_agent.atp.wire.CallerChecks
import com.nujan_agent.atp.wire.CloseReason
import com.nujan_agent.atp.wire.OpenStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * The app as an ATP provider (SPEC.md 4). Exported with no permission, because every call is checked against what the
 * platform reports about the caller, and a channel is served only to an app the person connected. The kit's manifest
 * declares it, pointing hosts at the app's `res/xml/atp_provider.xml`.
 */
class AtpProviderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine by lazy { Engine.of(this) }
    private val channels: Channels get() = engine.channels

    /** Puts the questions about calls in groups set to ask to the person, one at a time across all channels. */
    private val asker by lazy { CallAsk(this, onScreen = { AppVisibility.onScreen }) }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        channels.all().forEach { it.server.close(CloseReason.PROVIDER_SHUTDOWN, "${engine.label} is stopping.") }
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IAtpProvider.Stub() {

        override fun hello(helloJson: ByteArray?): ByteArray {
            val (caller, _) = SigningCertificates.callerFacts(packageManager, Binder.getCallingUid())
            return engine.policy.hello(caller, helloJson ?: ByteArray(0))
        }

        override fun openChannel(
            serverId: String?,
            toProvider: ParcelFileDescriptor?,
            fromProvider: ParcelFileDescriptor?,
            host: IAtpHost?,
            openJson: ByteArray?,
        ): ByteArray {
            val uid = Binder.getCallingUid()
            fun refuse(status: OpenStatus, detail: String): ByteArray {
                listOfNotNull(toProvider, fromProvider).forEach { runCatching { it.close() } }
                return status.toJson(detail)
            }
            if (serverId == null || toProvider == null || fromProvider == null || host == null) {
                return refuse(OpenStatus.BAD_STREAM, "A descriptor or the host callback is missing.")
            }
            val (caller, subject) = SigningCertificates.callerFacts(packageManager, uid)
            val held = channels.of(uid).size
            val open = when (val decision = engine.policy.open(caller, serverId, openJson ?: ByteArray(0), held)) {
                is OpenDecision.Refused -> return refuse(decision.status, decision.detail)
                is OpenDecision.Open -> decision
            }
            if (!isPipe(toProvider) || !isPipe(fromProvider)) return refuse(OpenStatus.BAD_STREAM, "A descriptor is not a pipe.")
            if (!closeOnExec(toProvider) || !closeOnExec(fromProvider)) {
                return refuse(OpenStatus.TRANSPORT_UNAVAILABLE, "The descriptors cannot be kept from child processes.")
            }
            val pinned = AtpProvider.trustedHosts.isPinned(open.packageName, CallerChecks.signerOf(caller.currentSigners), subject)
            // A bound app can be seen now, so its name is kept for the Connected apps screen.
            appLabel(packageManager, open.packageName)?.let { engine.policy.named(open.packageName, it) }
            serve(uid, AtpCaller(open.packageName, open.signer, pinned), serverId, toProvider, fromProvider, host)
            return OpenStatus.OK.toJson()
        }

        override fun closeChannel(serverId: String?, reason: Int) {
            val uid = Binder.getCallingUid()
            val why = CloseReason.of(reason) ?: CloseReason.NORMAL
            channels.of(uid).filter { it.serverId == serverId }.forEach { it.server.close(why, "The app closed the channel.") }
        }

        override fun uiResult(serverId: String?, requestId: Int, resultCode: Int) {
            val uid = Binder.getCallingUid()
            channels.of(uid).filter { it.serverId == serverId }.forEach { it.ui?.answered(requestId, resultCode) }
        }

        override fun releaseGrant() {
            val uid = Binder.getCallingUid()
            val (caller, _) = SigningCertificates.callerFacts(packageManager, uid)
            engine.policy.release(caller)
            channels.of(uid).forEach { it.server.close(CloseReason.NORMAL, "The app disconnected.") }
            caller.packages.singleOrNull()?.let { AtpProvider.onDisconnected(it) }
        }
    }

    /** Serves one channel to [caller]; what it may use is read from its record on every request. */
    private fun serve(
        uid: Int,
        caller: AtpCaller,
        serverId: String,
        toProvider: ParcelFileDescriptor,
        fromProvider: ParcelFileDescriptor,
        host: IAtpHost,
    ) {
        val packageName = caller.packageName
        val appName = appLabel(packageManager, packageName) ?: packageName
        val ui = ChannelUi(
            onScreen = { AppVisibility.onScreen },
            startDirectly = { intent -> startActivity(ownActivity(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            requestFromHost = { requestId, intent, reasonJson ->
                val pending = PendingIntent.getActivity(
                    this, requestId, ownActivity(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                host.onUiRequest(requestId, pending, reasonJson)
            },
            hostsCheckHandOffs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        lateinit var server: ChannelServer
        val dispatcher = ToolDispatcher(
            tools = { engine.tools(serverId, caller) },
            caller = caller,
            grant = { engine.store.find(packageName)?.takeUnless { it.blocked }?.grant },
            serverName = this.packageName,
            serverVersion = engine.versionName,
            providerLabel = engine.label,
            grantsPlace = getString(R.string.atp_grants_place, engine.label),
            groupTitle = engine::groupTitle,
            warn = { Log.w(TAG, it) },
            ui = ui,
            instructions = { engine.instructions(serverId) },
            ask = { tool, opened -> asker.ask(appName, toolTitle(tool), engine.groupTitle(tool.group.orEmpty()), opened) },
            asking = { tool, token, asking ->
                val about = getString(R.string.atp_quoted, toolTitle(tool))
                server.notifyAsking(token, asking, getString(R.string.atp_asking_progress, engine.label, about))
            },
            turnEnded = { turn -> engine.turnEnded(serverId, caller, turn) },
        )
        lateinit var opened: Channels.Open
        lateinit var death: IBinder.DeathRecipient
        server = ChannelServer(
            input = ParcelFileDescriptor.AutoCloseInputStream(toProvider),
            output = ParcelFileDescriptor.AutoCloseOutputStream(fromProvider),
            dispatcher = dispatcher,
            scope = scope,
            onClosed = { reason, detail ->
                channels.remove(opened)
                runCatching { host.asBinder().unlinkToDeath(death, 0) }
                runCatching { host.onClosed(reason.value, reason.detailJson(detail)) }
            },
        )
        death = IBinder.DeathRecipient { server.close(CloseReason.NORMAL, "The app's process ended.") }
        opened = Channels.Open(uid, packageName, serverId, server, ui)
        channels.add(opened)
        try {
            host.asBinder().linkToDeath(death, 0)
        } catch (gone: RemoteException) {
            server.close(CloseReason.NORMAL, "The app's process ended.")
            return
        }
        server.start()
    }

    /** [intent] when it names one of this app's own components; the hand-off starts nothing else (SPEC.md 9.1). */
    private fun ownActivity(intent: Intent): Intent {
        require(intent.component?.packageName == packageName) { "a call may show only this app's own activities" }
        return intent
    }

    private fun isPipe(descriptor: ParcelFileDescriptor): Boolean = try {
        OsConstants.S_ISFIFO(Os.fstat(descriptor.fileDescriptor).st_mode)
    } catch (unreadable: ErrnoException) {
        false
    }

    /**
     * Keeps [descriptor] out of every child process. Below API 30 app code cannot set the flag, but Binder delivers
     * descriptors with it set, so there it is confirmed instead.
     */
    private fun closeOnExec(descriptor: ParcelFileDescriptor): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            true
        } else {
            isCloseOnExec(descriptor.fd)
        }
    } catch (refused: ErrnoException) {
        false
    }

    private fun isCloseOnExec(fd: Int): Boolean {
        val flags = runCatching {
            File("/proc/self/fdinfo/$fd").readLines().firstOrNull { it.startsWith("flags:") }
                ?.substringAfter(':')?.trim()?.toLong(8)
        }.getOrNull() ?: return false
        return (flags and O_CLOEXEC) != 0L
    }

    private companion object {
        const val TAG = "AtpProvider"

        /** Linux's O_CLOEXEC, as `/proc/self/fdinfo` prints open flags. */
        const val O_CLOEXEC = 0x80000L
    }
}
