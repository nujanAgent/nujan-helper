package com.nujan.helper.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OutputTrimTest {
    @Test
    fun `short output passes through unchanged`() {
        val data = "hello world".toByteArray()
        assertEquals("hello world", OutputTrim.forMcp(data, limitBytes = 1024))
    }

    @Test
    fun `oversized output is head+tail trimmed with a total-bytes marker`() {
        val data = ByteArray(1000) { 'a'.code.toByte() }
        val out = OutputTrim.forMcp(data, limitBytes = 200, totalBytes = 5000)
        assertTrue(out.contains("[...output trimmed, 5000 bytes total...]"))
        assertTrue("trimmed body must be smaller than the source", out.toByteArray().size < data.size)
    }

    @Test
    fun `marker fires when total exceeds the buffered tail even if the tail fits`() {
        val data = ByteArray(100) { 'x'.code.toByte() }
        val out = OutputTrim.forMcp(data, limitBytes = 1024, totalBytes = 9999)
        assertTrue(out.contains("9999 bytes total"))
    }

    @Test
    fun `output reads as the terminal shows it, not every frame a progress line drew`() {
        val data = "\r0% [Working]\r            \rHit:1 bookworm InRelease\r\n\u001b[32mDone\u001b[0m\r\nEnter a city: ".toByteArray()
        assertEquals("Hit:1 bookworm InRelease\nDone\nEnter a city:", OutputTrim.forMcp(data, limitBytes = 1024))
    }

    @Test
    fun `an escape sequence cut at the edge of the kept head cannot swallow the marker`() {
        val total = 2_000L
        val marker = "\n[...output trimmed, $total bytes total...]\n"
        val head = (200 - marker.toByteArray().size) / 2
        val data = ("x".repeat(head - 3) + "\u001b[3" + "1m" + "y".repeat(500)).toByteArray()

        val out = OutputTrim.forMcp(data, limitBytes = 200, totalBytes = total)

        assertTrue(out, out.startsWith("x".repeat(head - 3) + marker))
    }
}

class JobLogTest {
    @Test
    fun `ring keeps the newest bytes and total counts everything`() {
        val log = JobLog(capacity = 8)
        log.append("abcdef".toByteArray(), 6)
        log.append("ghijkl".toByteArray(), 6)
        assertEquals(12, log.total)
        assertEquals("efghijkl", log.snapshot().decodeToString()) // last 8 bytes
    }

    @Test
    fun `a chunk larger than capacity keeps only its tail`() {
        val log = JobLog(capacity = 4)
        log.append("abcdefgh".toByteArray(), 8)
        assertEquals(8, log.total)
        assertEquals("efgh", log.snapshot().decodeToString())
    }

    @Test
    fun `the buffer grows with the output, so a short job holds little`() {
        val log = JobLog(capacity = 2 * 1024 * 1024)
        log.append("JOBOK\n".toByteArray(), 6)
        assertEquals(4 * 1024, log.allocated)
        val big = ByteArray(100_000) { 'x'.code.toByte() }
        log.append(big, big.size)
        assertTrue(log.allocated in 100_006..2 * 1024 * 1024)
        assertEquals("JOBOK\n" + "x".repeat(100_000), log.snapshot().decodeToString())
    }

    @Test
    fun `a finished job keeps both ends of its output and gives the rest back`() {
        val log = JobLog(capacity = 64)
        log.append("0123456789".toByteArray(), 10)
        log.keepEnds(4)
        assertEquals("0189", log.snapshot().decodeToString())
        assertEquals(4, log.allocated)
        assertEquals(10, log.total)

        val short = JobLog(capacity = 64)
        short.append("abc".toByteArray(), 3)
        short.keepEnds(4)
        assertEquals("abc", short.snapshot().decodeToString())
        assertEquals(3, short.allocated)
    }

    @Test
    fun `what a finished log gives for a limit is what the whole buffer gave`() {
        val text = ("BEGIN " + "0123456789".repeat(500) + " END").toByteArray()
        for (limit in listOf(100, 1000, 2000)) {
            val whole = JobLog(capacity = 64 * 1024).apply { append(text, text.size) }
            val before = OutputTrim.forMcp(whole.snapshot(), limit, whole.total)
            whole.keepEnds(2000)
            assertEquals("limit $limit", before, OutputTrim.forMcp(whole.snapshot(), limit, whole.total))
        }
    }
}

class PtyLaunchJobTest {
    private val layout = RuntimeLayout("/f")

    @Test
    fun `job runs the guest command through a login bash under a clean env`() {
        val argv = ProotCommand.argv(PtyLaunch.job(layout, listOf("npm", "install")))
        assertTrue(argv.containsSubsequence(listOf("/usr/bin/env", "-i", "HOME=/root")))
        assertTrue(argv.containsSubsequence(listOf("/bin/bash", "-lc", "'npm' 'install'")))
    }

    @Test
    fun `shellQuote neutralizes embedded single quotes`() {
        assertEquals("""'it'\''s'""", PtyLaunch.shellQuote("it's"))
    }
}

class JobEngineTest {
    private fun engine(
        launcher: JobLauncher,
        maxConcurrent: Int = 2,
        onTerminal: (Job) -> Unit = {},
    ) = JobEngine(
        launcher = launcher,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        maxConcurrent = maxConcurrent,
        killGraceMs = 50,
        onTerminal = onTerminal,
    )

    /**
     * Fake proot process: emits [outputBytes], then exits [exit] — or blocks until SIGKILL. With [holdOutput], its output
     * stays open after the bytes until the latch is released, as a terminal does while something still holds it.
     */
    private class FakeProcess(
        outputBytes: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        blockUntilSignal: Boolean = false,
        private val holdOutput: CountDownLatch? = null,
    ) : JobProcess {
        /** Released at the engine's first read of the output, which comes once the process is its job's. */
        val reading = CountDownLatch(1)
        /** Released as [waitFor] returns. */
        val waited = CountDownLatch(1)
        private val bytes = ByteArrayInputStream(outputBytes)
        override val output: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reading.countDown()
                val n = bytes.read(b, off, len)
                if (n < 0) holdOutput?.await()
                return n
            }
        }
        val signals = CopyOnWriteArrayList<Int>()
        val typed = java.io.ByteArrayOutputStream()
        override fun write(bytes: ByteArray) { synchronized(typed) { typed.write(bytes) } }
        @Volatile var closed = false
        @Volatile private var effectiveExit = exit
        private val exitLatch = CountDownLatch(1)
        init { if (!blockUntilSignal) exitLatch.countDown() }
        override fun waitFor(): Int { exitLatch.await(); waited.countDown(); return effectiveExit }
        override fun signal(sig: Int) {
            signals += sig
            if (sig == Signals.SIGKILL) { effectiveExit = 137; exitLatch.countDown() }
        }
        override fun close() { closed = true }
    }

    private fun awaitState(e: JobEngine, id: String, want: JobState, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (e.status(id)?.state == want) return
            Thread.sleep(10)
        }
        throw AssertionError("job $id never reached $want (was ${e.status(id)?.state})")
    }

    @Test
    fun `successful job captures output, exits 0, and reaches DONE`() = runBlocking {
        val terminal = CopyOnWriteArrayList<Job>()
        val e = engine({ FakeProcess("build ok\n".toByteArray(), exit = 0) }, onTerminal = { terminal += it })
        val job = e.submit(JobSpec(command = listOf("echo", "build ok")))
        val final = e.await(job.id)!!
        assertEquals(JobState.DONE, final.state)
        assertEquals(0, final.exitCode)
        assertTrue(e.tail(job.id)!!.contains("build ok"))
        assertEquals(1, terminal.size)
        assertTrue(final.startedAt != null && final.endedAt != null)
    }

    @Test
    fun `finished jobs past the limit are forgotten, oldest first, and the newest keep their output`() = runBlocking {
        val e = JobEngine(
            launcher = { FakeProcess("done\n".toByteArray(), exit = 0) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            keepFinished = 3,
        )
        val ids = (1..5).map { n -> e.submit(JobSpec(listOf("echo", "$n"))).id.also { e.await(it) } }

        assertEquals(3, e.list().size)
        assertNull("the oldest is forgotten", e.status(ids[0]))
        assertNull(e.status(ids[1]))
        assertEquals(JobState.DONE, e.status(ids[4])?.state)
        assertTrue(e.tail(ids[4])!!.contains("done"))
    }

    @Test
    fun `a finished job's output keeps its start and its end`() = runBlocking {
        val out = "BEGIN" + "x".repeat(10_000) + "END"
        val e = JobEngine(
            launcher = { FakeProcess(out.toByteArray(), exit = 1) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            finishedKeepBytes = 1024,
        )
        val id = e.submit(JobSpec(listOf("make"))).id
        e.await(id)

        val tail = e.tail(id)!!
        assertTrue(tail, tail.startsWith("BEGIN") && tail.endsWith("END"))
        assertTrue(tail, tail.contains("[...output trimmed, 10008 bytes total...]"))
        assertTrue("asked for more, it gives what it kept", tail.length <= 1024)
    }

    @Test
    fun `nonzero exit is FAILED and carries the exit code`() = runBlocking {
        val e = engine({ FakeProcess("boom\n".toByteArray(), exit = 3) })
        val final = e.await(e.submit(JobSpec(listOf("false"))).id)!!
        assertEquals(JobState.FAILED, final.state)
        assertEquals(3, final.exitCode)
    }

    @Test
    fun `a launch failure is FAILED with an error, not a crash`() = runBlocking {
        val e = engine({ throw IllegalStateException("forkpty failed") })
        val final = e.await(e.submit(JobSpec(listOf("x"))).id)!!
        assertEquals(JobState.FAILED, final.state)
        assertTrue(final.error!!.contains("forkpty failed"))
    }

    @Test
    fun `a job that overruns its timeout is TIMEOUT and gets killed`() = runBlocking {
        val proc = FakeProcess(blockUntilSignal = true)
        val e = engine({ proc })
        val final = e.await(e.submit(JobSpec(listOf("sleep", "999"), timeoutMs = 80)).id)!!
        assertEquals(JobState.TIMEOUT, final.state)
        assertTrue("should escalate to SIGKILL", proc.signals.contains(Signals.SIGKILL))
        assertTrue(proc.closed)
    }

    @Test
    fun `a job belongs to the app that started it, which alone sees it`() {
        val e = engine({ FakeProcess() })
        val job = e.submit(JobSpec(listOf("true"), owner = "com.example.one"))
        assertEquals("com.example.one", e.status(job.id)!!.owner)
        assertTrue(job.visibleTo("com.example.one"))
        assertFalse("another app does not see it", job.visibleTo("com.example.two"))
        assertTrue("the Helper's own screens see every job", job.visibleTo(null))
        assertFalse("an app does not see the Helper's own", e.submit(JobSpec(listOf("true"))).visibleTo("com.example.one"))
    }

    /** The name the Helper gives its own work stays with the job to its end, and a command an app runs has none. */
    @Test
    fun `a job keeps the label it was submitted with`() = runBlocking {
        val e = engine({ FakeProcess() })
        val named = e.submit(JobSpec(listOf("sh", "-c", "apt-get install -y python3-pip"), label = "Installing Python tools"))
        assertEquals("Installing Python tools", named.label)
        assertEquals("Installing Python tools", e.await(named.id)!!.label)
        assertEquals("", e.submit(JobSpec(listOf("ls"))).label)
    }

    @Test
    fun `stopping an app's work stops its jobs and leaves the others running`() = runBlocking {
        val e = engine({ FakeProcess(blockUntilSignal = true) }, maxConcurrent = 3)
        val one = e.submit(JobSpec(listOf("sleep", "999"), owner = "com.example.one"))
        val two = e.submit(JobSpec(listOf("sleep", "999"), owner = "com.example.two"))
        awaitState(e, one.id, JobState.RUNNING)
        awaitState(e, two.id, JobState.RUNNING)

        e.cancelOwnedBy("com.example.one")

        assertEquals(JobState.KILLED, e.await(one.id)!!.state)
        assertEquals(JobState.RUNNING, e.status(two.id)!!.state)
        e.cancel(two.id)
    }

    /** A running job takes typed input in its terminal, the answer to a question it waits at. */
    @Test
    fun `typed input reaches a running job`() = runBlocking {
        val proc = FakeProcess(blockUntilSignal = true)
        val e = engine({ proc })
        val job = e.submit(JobSpec(listOf("npm", "init")))
        awaitState(e, job.id, JobState.RUNNING)
        assertTrue("its terminal is open", proc.reading.await(3, TimeUnit.SECONDS))

        assertTrue(e.input(job.id, "my-app\r".toByteArray()))
        assertEquals("my-app\r", synchronized(proc.typed) { proc.typed.toString(Charsets.UTF_8.name()) })
        e.cancel(job.id)
        e.await(job.id)
        Unit
    }

    /** A job that has ended, or one the engine never ran, takes no input, so the caller can say so. */
    @Test
    fun `a job that has ended takes no input`() = runBlocking {
        val e = engine({ FakeProcess("done\n".toByteArray(), exit = 0) })
        val job = e.submit(JobSpec(listOf("true")))
        e.await(job.id)

        assertFalse(e.input(job.id, "y\r".toByteArray()))
        assertFalse(e.input("no-such-job", "y\r".toByteArray()))
    }

    @Test
    fun `cancel stops a running job and reports KILLED`() = runBlocking {
        val proc = FakeProcess(blockUntilSignal = true)
        val e = engine({ proc })
        val job = e.submit(JobSpec(listOf("sleep", "999")))
        awaitState(e, job.id, JobState.RUNNING)
        e.cancel(job.id)
        val final = e.await(job.id)!!
        assertEquals(JobState.KILLED, final.state)
    }

    @Test
    fun `activeCount tracks non-terminal jobs and returns to zero`() = runBlocking {
        val proc = FakeProcess(blockUntilSignal = true)
        val e = engine({ proc })
        assertEquals(0, e.activeCount.value)
        val job = e.submit(JobSpec(listOf("sleep", "999")))
        awaitState(e, job.id, JobState.RUNNING)
        assertEquals(1, e.activeCount.value)
        e.cancel(job.id)
        e.await(job.id)
        assertEquals(0, e.activeCount.value)
    }

    @Test
    fun `concurrency cap keeps extra jobs QUEUED`() = runBlocking {
        val e = engine({ FakeProcess(blockUntilSignal = true) }, maxConcurrent = 1)
        val a = e.submit(JobSpec(listOf("a")))
        val b = e.submit(JobSpec(listOf("b")))
        awaitState(e, a.id, JobState.RUNNING)
        // b must still be waiting for the single permit.
        Thread.sleep(50)
        assertEquals(JobState.QUEUED, e.status(b.id)!!.state)
        assertFalse(e.status(b.id)!!.state.terminal)
        e.cancel(a.id); e.cancel(b.id)
        e.await(a.id); e.await(b.id)
        assertNull(null) // reached here without deadlock
    }

    /** A job says from its submission whether it waits: RUNNING when it takes a free slot, QUEUED until one frees. */
    @Test
    fun `a job is QUEUED from its submission while it waits for a slot, and RUNNING once it has one`() = runBlocking {
        val e = engine({ FakeProcess(blockUntilSignal = true) }, maxConcurrent = 1)
        val first = e.submit(JobSpec(listOf("first")))
        val second = e.submit(JobSpec(listOf("second")))

        assertEquals(JobState.RUNNING, first.state)
        assertEquals(JobState.QUEUED, second.state)
        e.cancel(first.id)
        awaitState(e, second.id, JobState.RUNNING)
        e.cancel(second.id)
        e.await(second.id)
        Unit
    }

    /** Servers run until stopped, so they have slots of their own; with them full, a command still runs at once. */
    @Test
    fun `servers left running do not hold up a command, and a server past their slots waits`() = runBlocking {
        val e = engine(
            { spec -> if (spec.server) FakeProcess(blockUntilSignal = true) else FakeProcess("ok\n".toByteArray()) },
            maxConcurrent = 1,
        )
        val servers = List(e.maxServers) { e.submit(JobSpec(listOf("npm", "run", "dev"), server = true)) }
        servers.forEach { assertEquals(JobState.RUNNING, it.state) }

        val command = e.submit(JobSpec(listOf("ls")))
        assertEquals(JobState.RUNNING, command.state)
        assertEquals(JobState.DONE, withTimeout(3_000) { e.await(command.id) }!!.state)

        val another = e.submit(JobSpec(listOf("npm", "start"), server = true))
        assertTrue(another.server)
        assertEquals(JobState.QUEUED, another.state)
        (servers + another).forEach { e.cancel(it.id) }
        (servers + another).forEach { e.await(it.id) }
    }

    /** A job stopped while it waits for a slot ends at once and is never started. */
    @Test
    fun `a job stopped while it waits for a slot ends at once and never starts`() = runBlocking {
        val launched = CopyOnWriteArrayList<String>()
        val firstProcess = FakeProcess(blockUntilSignal = true)
        val e = engine(
            { spec ->
                launched += spec.command.first()
                when (spec.command.first()) {
                    "first" -> firstProcess
                    "third" -> FakeProcess(exit = 0)
                    else -> FakeProcess(blockUntilSignal = true)
                }
            },
            maxConcurrent = 1,
        )
        val first = e.submit(JobSpec(listOf("first")))
        val second = e.submit(JobSpec(listOf("second")))
        assertTrue("the first job's process is up", firstProcess.reading.await(3, TimeUnit.SECONDS))

        e.cancel(second.id)

        assertEquals(JobState.KILLED, withTimeout(3_000) { e.await(second.id) }!!.state)
        assertEquals("the running job is untouched", JobState.RUNNING, e.status(first.id)!!.state)
        e.cancel(first.id)
        e.await(first.id)
        // The slot passes in order, so the stopped job had its turn before this one runs.
        e.await(e.submit(JobSpec(listOf("third"))).id)
        assertEquals(listOf("first", "third"), launched)
    }

    /**
     * A stop that comes after the process has ended, while its output is still being read, sends no signal, since the
     * pid may already be another process's, and the job keeps the outcome it ended with.
     */
    @Test
    fun `a stop after the process has ended neither signals it nor marks the job stopped`() = runBlocking {
        val output = CountDownLatch(1)
        val proc = FakeProcess("built\n".toByteArray(), exit = 0, holdOutput = output)
        val e = engine({ proc })
        val job = e.submit(JobSpec(listOf("make")))
        assertTrue(proc.waited.await(3, TimeUnit.SECONDS))
        // The engine notes the exit as the wait returns; the pause leaves it time to.
        Thread.sleep(200)

        e.cancel(job.id)
        output.countDown()

        val final = withTimeout(3_000) { e.await(job.id) }!!
        assertEquals(JobState.DONE, final.state)
        assertEquals(0, final.exitCode)
        assertEquals(emptyList<Int>(), proc.signals.toList())
    }
}

private fun <T> List<T>.containsSubsequence(sub: List<T>): Boolean {
    if (sub.isEmpty()) return true
    for (i in 0..size - sub.size) if (subList(i, i + sub.size) == sub) return true
    return false
}
