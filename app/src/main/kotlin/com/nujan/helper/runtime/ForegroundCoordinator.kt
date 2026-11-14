package com.nujan.helper.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs [RuntimeService] while any job is queued or running, or Linux is being set up, which keeps the process out of
 * Doze and off the phantom-process killer's list and the processor awake with the screen off. An app using the Helper
 * keeps it running through its ATP binding; the service is for work that outlives the calls that started it.
 *
 * Each change of work starts the service or updates its notification, and the first idle after work stops it once.
 * Only a service this coordinator started is stopped, so a Helper with no work never starts the service just to stop it.
 *
 * Once no app has a channel open, [onNoAppConnected] tears down what only a connected app can use, the hosted MCP
 * servers, so no rootfs child is left running for nobody.
 */
class ForegroundCoordinator(
    /** The jobs queued or running. */
    private val activeJobs: StateFlow<Int>,
    /** True while the first-run setup downloads, extracts or installs. */
    private val settingUp: StateFlow<Boolean>,
    /** The packages with an ATP channel open now. */
    private val connected: StateFlow<Set<String>>,
    private val scope: CoroutineScope,
    /** True while waiting behind Android's settings for its pairing dialog, which a frozen process would miss. */
    private val pairing: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    /** Starts the service, or updates its notification, with the jobs active and whether Linux is being set up. */
    private val startService: (active: Int, settingUp: Boolean) -> Unit,
    /** Stops the service unless a start sent later reaches it ([RuntimeService.stop]). */
    private val stopService: () -> Unit,
    /** Idempotent. */
    private val onNoAppConnected: () -> Unit,
) {
    fun start() {
        scope.launch {
            var started = false
            combine(activeJobs, settingUp, pairing) { active, setup, waiting -> Triple(active, setup, waiting) }.collect { (active, setup, waiting) ->
                if (active > 0 || setup || waiting) {
                    startService(active, setup)
                    started = true
                } else if (started) {
                    stopService()
                    started = false
                }
            }
        }
        scope.launch {
            connected.collect { packages -> if (packages.isEmpty()) onNoAppConnected() }
        }
    }
}
