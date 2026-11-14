// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A slow reader costs the writer bounded memory, and what is dropped or merged is never a response. */
class WriteQueueTest {

    @Test
    fun `lines come out in the order offered`() {
        val q = WriteQueue()
        q.offer("a")
        q.offer("b")
        assertEquals(listOf("a", "b"), listOf(q.poll(), q.poll()))
        assertNull(q.poll())
        assertTrue(q.isEmpty)
        assertEquals(0L, q.queuedBytes)
    }

    @Test
    fun `a waiting list change absorbs the next and a written one does not`() {
        val q = WriteQueue()
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("changed-1", WriteQueue.Kind.LIST_CHANGED))
        assertEquals(WriteQueue.Offer.COALESCED, q.offer("changed-2", WriteQueue.Kind.LIST_CHANGED))
        assertEquals("changed-1", q.poll())
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("changed-3", WriteQueue.Kind.LIST_CHANGED))
    }

    @Test
    fun `progress keeps only the latest notice per token, in the first one's place`() {
        val q = WriteQueue()
        q.offer("p1-10%", WriteQueue.Kind.PROGRESS, progressToken = "t1")
        q.offer("reply", WriteQueue.Kind.MESSAGE)
        assertEquals(WriteQueue.Offer.COALESCED, q.offer("p1-90%", WriteQueue.Kind.PROGRESS, progressToken = "t1"))
        q.offer("p2-50%", WriteQueue.Kind.PROGRESS, progressToken = "t2")
        assertEquals(listOf("p1-90%", "reply", "p2-50%"), listOf(q.poll(), q.poll(), q.poll()))
        assertEquals(0L, q.queuedBytes)
    }

    @Test
    fun `logs are dropped once the queue is half full`() {
        val q = WriteQueue(capacityBytes = 100)
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("x".repeat(40), WriteQueue.Kind.LOG))
        assertEquals(WriteQueue.Offer.DROPPED, q.offer("y".repeat(20), WriteQueue.Kind.LOG))
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("z".repeat(20), WriteQueue.Kind.MESSAGE))
    }

    @Test
    fun `a message that does not fit is refused, not dropped`() {
        val q = WriteQueue(capacityBytes = 100)
        q.offer("x".repeat(80))
        assertEquals(WriteQueue.Offer.FULL, q.offer("y".repeat(30)))
        assertEquals("x".repeat(80), q.poll())
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("y".repeat(30)))
    }

    @Test
    fun `a notice is kept like a message where a log would be dropped, and keeps its kind`() {
        val q = WriteQueue(capacityBytes = 100)
        q.offer("x".repeat(60))
        assertEquals(WriteQueue.Offer.DROPPED, q.offer("log", WriteQueue.Kind.LOG))
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("stopped-1", WriteQueue.Kind.NOTICE))
        assertEquals(WriteQueue.Offer.QUEUED, q.offer("stopped-2", WriteQueue.Kind.NOTICE))
        q.poll()
        assertEquals(WriteQueue.Kind.NOTICE, q.pollEntry()!!.kind)
        assertEquals("stopped-2", q.poll())
    }

    @Test
    fun `a progress notice needs its token`() {
        assertThrows(IllegalArgumentException::class.java) { WriteQueue().offer("p", WriteQueue.Kind.PROGRESS) }
    }
}
