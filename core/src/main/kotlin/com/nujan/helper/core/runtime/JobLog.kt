package com.nujan.helper.core.runtime

/**
 * A bounded ring buffer of a job's most recent output. Keeps the
 * last [capacity] bytes in memory for `job_tail`/status while [total] tracks everything ever written
 * (the full log is persisted to disk by the `:app` sink). Thread-safe: the reader thread appends
 * while the UI/MCP thread snapshots.
 *
 * The buffer starts small and grows with the output, so a job that prints a line holds a few kilobytes rather than the
 * whole [capacity]; [keepEnds] gives back what a finished job no longer needs.
 */
class JobLog(val capacity: Int = 2 * 1024 * 1024) {
    private var ring = ByteArray(minOf(capacity, INITIAL_BYTES))
    private var size = 0
    private var start = 0

    /** Total bytes ever appended (may exceed [capacity]; older bytes are dropped from the ring). */
    var total: Long = 0
        private set

    /** Bytes the buffer holds in memory now, for tests and accounting. */
    val allocated: Int @Synchronized get() = ring.size

    @Synchronized
    fun append(bytes: ByteArray, len: Int) {
        total += len
        if (len >= capacity) {
            // The new chunk alone overflows: keep only its last [capacity] bytes.
            if (ring.size < capacity) ring = ByteArray(capacity)
            System.arraycopy(bytes, len - capacity, ring, 0, capacity)
            start = 0; size = capacity
            return
        }
        if (size + len > ring.size && ring.size < capacity) resize(minOf(capacity, maxOf(ring.size * 2, size + len)))
        for (i in 0 until len) {
            ring[(start + size) % ring.size] = bytes[i]
            if (size < ring.size) size++ else start = (start + 1) % ring.size
        }
    }

    /** A copy of the currently buffered bytes (oldest→newest), at most [capacity] long. */
    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = ring[(start + i) % ring.size]
        return out
    }

    /**
     * For a finished job: keeps the first and the last half of [bytes] of what is buffered, and only that much memory.
     * [OutputTrim.forMcp] given no more than [bytes] then reads the same head and tail it read from the whole buffer.
     */
    @Synchronized
    fun keepEnds(bytes: Int) {
        val head = if (size > bytes) bytes / 2 else size
        val tail = if (size > bytes) bytes - head else 0
        val kept = ByteArray(maxOf(head + tail, 1))
        for (i in 0 until head) kept[i] = ring[(start + i) % ring.size]
        for (i in 0 until tail) kept[head + i] = ring[(start + size - tail + i) % ring.size]
        ring = kept
        start = 0
        size = head + tail
    }

    private fun resize(newSize: Int) {
        val grown = ByteArray(newSize)
        for (i in 0 until size) grown[i] = ring[(start + i) % ring.size]
        ring = grown
        start = 0
    }

    private companion object {
        const val INITIAL_BYTES = 4 * 1024
    }
}
