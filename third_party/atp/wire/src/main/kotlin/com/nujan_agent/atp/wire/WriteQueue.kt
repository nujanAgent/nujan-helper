// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

/**
 * The bounded queue in front of a channel's one writer (SPEC.md 3.2): a list-change notice already waiting
 * absorbs the next one, a progress notice replaces the waiting one for the same token, logs are dropped once
 * the queue is half full, and anything else that does not fit is refused, which the caller treats as a stall.
 *
 * Logic only: the caller owns the thread that polls it and writes. Not thread-safe; guard it with one lock.
 */
class WriteQueue(private val capacityBytes: Long = Atp.WRITE_QUEUE_BYTES) {

    /** [MESSAGE] is an answer; the rest are notifications, and [NOTICE] is one that is neither coalesced nor dropped. */
    enum class Kind { MESSAGE, LIST_CHANGED, PROGRESS, LOG, NOTICE }

    enum class Offer { QUEUED, COALESCED, DROPPED, FULL }

    private class Slot(var line: String, var bytes: Int, val kind: Kind, val token: String?)

    private val slots = ArrayDeque<Slot>()
    private val progressByToken = HashMap<String, Slot>()
    private var listChangedWaiting = false

    /** Bytes waiting to be written. */
    var queuedBytes: Long = 0
        private set

    val isEmpty: Boolean get() = slots.isEmpty()

    /** Offers [line]; [progressToken] is required for [Kind.PROGRESS]. */
    fun offer(line: String, kind: Kind = Kind.MESSAGE, progressToken: String? = null): Offer {
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1
        when (kind) {
            Kind.LIST_CHANGED -> if (listChangedWaiting) return Offer.COALESCED
            Kind.PROGRESS -> {
                requireNotNull(progressToken) { "a progress notice needs its token" }
                progressByToken[progressToken]?.let { waiting ->
                    if (queuedBytes - waiting.bytes + bytes > capacityBytes) return Offer.FULL
                    queuedBytes += bytes - waiting.bytes
                    waiting.line = line
                    waiting.bytes = bytes
                    return Offer.COALESCED
                }
            }
            Kind.LOG -> if (queuedBytes + bytes > capacityBytes / 2) return Offer.DROPPED
            Kind.MESSAGE, Kind.NOTICE -> Unit
        }
        if (queuedBytes + bytes > capacityBytes) return Offer.FULL
        val slot = Slot(line, bytes, kind, progressToken)
        slots.addLast(slot)
        queuedBytes += bytes
        if (kind == Kind.LIST_CHANGED) listChangedWaiting = true
        if (kind == Kind.PROGRESS) progressByToken[progressToken!!] = slot
        return Offer.QUEUED
    }

    /** A line to write and the kind it was offered as, which tells a notice from an answer. */
    class Entry(val line: String, val kind: Kind)

    /** The next line to write, in the order offered, or null when nothing waits. */
    fun poll(): String? = pollEntry()?.line

    /** The next line to write with its kind, in the order offered, or null when nothing waits. */
    fun pollEntry(): Entry? {
        val slot = slots.removeFirstOrNull() ?: return null
        queuedBytes -= slot.bytes
        when (slot.kind) {
            Kind.LIST_CHANGED -> listChangedWaiting = false
            Kind.PROGRESS -> progressByToken.remove(slot.token)
            else -> Unit
        }
        return Entry(slot.line, slot.kind)
    }
}
