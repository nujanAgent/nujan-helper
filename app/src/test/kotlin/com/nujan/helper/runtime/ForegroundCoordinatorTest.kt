package com.nujan.helper.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The foreground service runs while there is work: each change of work starts it or updates it, the first idle after
 * work stops it once, and a Helper that has had no work is never told to stop a service it did not start.
 */
class ForegroundCoordinatorTest {

    private val calls = CopyOnWriteArrayList<String>()
    private val active = MutableStateFlow(0)
    private val settingUp = MutableStateFlow(false)
    private val connected = MutableStateFlow(setOf("com.example.one"))

    // Unconfined, so each change is handled on the thread that makes it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val coordinator = ForegroundCoordinator(
        activeJobs = active,
        settingUp = settingUp,
        connected = connected,
        scope = scope,
        startService = { count, setup -> calls += "start $count $setup" },
        stopService = { calls += "stop" },
        onNoAppConnected = { calls += "no app" },
    )

    @After
    fun end() = scope.cancel()

    /** Waits until [count] calls have been made, at most three seconds. */
    private fun awaitCalls(count: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (calls.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun `a Helper with no work yet is never told to stop a service it did not start`() {
        coordinator.start()
        settingUp.value = true
        awaitCalls(1)

        assertEquals(listOf("start 0 true"), calls)
    }

    @Test
    fun `work starts the service, each change updates it, and the first idle after work stops it once`() {
        coordinator.start()

        active.value = 1
        awaitCalls(1)
        active.value = 2
        awaitCalls(2)
        active.value = 0
        awaitCalls(3)
        settingUp.value = true
        awaitCalls(4)
        settingUp.value = false
        awaitCalls(5)

        assertEquals(listOf("start 1 false", "start 2 false", "stop", "start 0 true", "stop"), calls)
    }

    @Test
    fun `what only a connected app can use ends once no app is connected`() {
        coordinator.start()
        connected.value = emptySet()
        awaitCalls(1)

        assertEquals(listOf("no app"), calls)
    }
}
