// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A consent nonce opens the consent screen once, for the app it was issued to, within five minutes. */
class NonceBookTest {

    private var clock = 1_000L
    private var counter = 0
    private val book = NonceBook(now = { clock }, randomBytes = { n -> ByteArray(n) { (counter++).toByte() } })
    private val binding = NonceBook.Binding(uid = 10_200, packageName = "com.nujan.app", signer = "aa", groups = setOf("files"))

    @Test
    fun `a nonce is 32 hex digits and comes back with what it was issued for`() {
        val nonce = book.issue(binding)
        assertEquals(Atp.NONCE_HEX_CHARS, nonce.length)
        assertEquals(binding, book.consume(nonce, uid = 10_200))
    }

    @Test
    fun `it is used up by any attempt, so a replay gets nothing`() {
        val nonce = book.issue(binding)
        assertNull("another app", book.consume(nonce, uid = 10_300))
        assertNull("the rightful app after the wrong one tried", book.consume(nonce, uid = 10_200))
    }

    @Test
    fun `it expires after five minutes`() {
        val nonce = book.issue(binding)
        clock += Atp.NONCE_TTL_MS + 1
        assertNull(book.consume(nonce, uid = 10_200))
    }

    @Test
    fun `issuing past the capacity drops the oldest`() {
        val small = NonceBook(now = { clock }, randomBytes = { n -> ByteArray(n) { (counter++).toByte() } }, capacity = 2)
        val first = small.issue(binding)
        val second = small.issue(binding)
        val third = small.issue(binding)
        assertNotEquals(first, second)
        assertNull(small.consume(first, uid = 10_200))
        assertEquals(binding, small.consume(second, uid = 10_200))
        assertEquals(binding, small.consume(third, uid = 10_200))
    }
}
