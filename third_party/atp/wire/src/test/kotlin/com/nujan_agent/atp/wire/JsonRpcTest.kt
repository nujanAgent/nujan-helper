// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each line is told apart by its shape, and a channel answers each request once, or not at all if cancelled. */
class JsonRpcTest {

    private fun kind(line: String) = JsonRpc.kindOf(JsonRpc.parse(line)!!)

    @Test
    fun `requests, notifications and responses are told apart`() {
        assertEquals(MessageKind.REQUEST, kind("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        assertEquals(MessageKind.NOTIFICATION, kind("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(MessageKind.NOTIFICATION, kind("""{"jsonrpc":"2.0","id":null,"method":"notifications/cancelled"}"""))
        assertEquals(MessageKind.RESPONSE, kind("""{"jsonrpc":"2.0","id":"a","result":{}}"""))
        assertEquals(MessageKind.RESPONSE, kind("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"x"}}"""))
        assertEquals(MessageKind.INVALID, kind("""{"jsonrpc":"2.0","id":3}"""))
        assertEquals(MessageKind.INVALID, kind("""{"method":7}"""))
        assertNull(JsonRpc.parse("not json"))
        assertNull(JsonRpc.parse("[1,2]"))
    }

    @Test
    fun `a channel holds at most its limit of requests in flight`() {
        val book = RequestBook(maxInFlight = 2)
        assertTrue(book.admit(JsonPrimitive(1)))
        assertTrue(book.admit(JsonPrimitive(2)))
        assertFalse("the limit", book.admit(JsonPrimitive(3)))
        assertFalse("an id already in flight", book.admit(JsonPrimitive(1)))
        assertTrue(book.finish(JsonPrimitive(1)))
        assertTrue(book.admit(JsonPrimitive(3)))
    }

    @Test
    fun `a cancelled request is not answered, and an unknown one is not either`() {
        val book = RequestBook()
        book.admit(JsonPrimitive("a"))
        assertTrue(book.cancel(JsonPrimitive("a")))
        assertFalse(book.finish(JsonPrimitive("a")))
        assertFalse(book.cancel(JsonPrimitive("never")))
        assertFalse(book.finish(JsonPrimitive("never")))
        assertEquals(0, book.size)
    }

    @Test
    fun `ids compare as JSON, so a number and the same digits as a string are different requests`() {
        val book = RequestBook()
        assertTrue(book.admit(JsonPrimitive(1)))
        assertTrue(book.admit(JsonPrimitive("1")))
        assertEquals(2, book.size)
    }
}
