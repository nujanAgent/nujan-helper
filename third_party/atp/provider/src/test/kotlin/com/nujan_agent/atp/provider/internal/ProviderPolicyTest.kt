// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import com.nujan_agent.atp.wire.Atp
import com.nujan_agent.atp.wire.CallerFacts
import com.nujan_agent.atp.wire.GroupState
import com.nujan_agent.atp.wire.HelloRequest
import com.nujan_agent.atp.wire.NonceBook
import com.nujan_agent.atp.wire.OpenRequest
import com.nujan_agent.atp.wire.OpenStatus
import com.nujan_agent.atp.wire.ServerInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider's answers to `hello` and `openChannel`: a grant comes only from a record the person made, `hello` reveals
 * only the caller's own state, a grant unused for 90 days expires, and an open is refused with the status the protocol
 * names for each reason.
 */
class ProviderPolicyTest {

    private class MemoryRecords : HostRecords {
        val byPackage = LinkedHashMap<String, HostRecord>()
        override fun find(packageName: String) = byPackage[packageName]
        override fun save(record: HostRecord) { byPackage[record.packageName] = record }
        override fun delete(packageName: String) { byPackage.remove(packageName) }
        override fun all() = byPackage.values.toList()
    }

    private val providerUid = 10_100
    private var clock = 1_000_000L
    private val records = MemoryRecords()
    private val servers = listOf(ServerInfo("main", listOf("2025-06-18")))

    // The fixtures were written against a provider of this name.
    private val label = "Nujan Helper"
    private val policy = ProviderPolicy(
        providerUid = providerUid,
        providerLabel = label,
        records = records,
        servers = servers,
        nonces = NonceBook(now = { clock }, randomBytes = { ByteArray(it) { 7 } }),
        now = { clock },
    )

    private val host = CallerFacts(uid = 10_200, packages = listOf("com.example.host"), currentSigners = setOf("aa11"))
    private val openJson = OpenRequest().toJson()
    private val filesOnly = grant("files" to GroupState.ON, "exec" to GroupState.OFF, "mcp" to GroupState.OFF)

    private fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    private fun grant(vararg states: Pair<String, GroupState>) = HostGrant(states.toMap())

    private fun record(blocked: Boolean = false, signer: String = "aa11", grant: HostGrant = filesOnly) =
        HostRecord("com.example.host", signer, blocked, grant, lastUsedMs = 0)

    @Test
    fun `an app with no record hears none, as the fixture has it`() {
        assertEquals(Fixtures.json("hello-response-none.json"), json(policy.hello(host, HelloRequest().toJson())))
    }

    @Test
    fun `a blocked app hears blocked and gets no nonce even when it asks`() {
        records.save(record(blocked = true))
        val answer = json(policy.hello(host, HelloRequest(wantNonce = true).toJson()))
        assertEquals(Fixtures.json("hello-response-blocked.json"), answer)
    }

    @Test
    fun `a granted app hears its groups, and a nonce when it asks`() {
        records.save(record(grant = grant("files" to GroupState.ON, "exec" to GroupState.ASK, "mcp" to GroupState.OFF)))
        val answer = json(policy.hello(host, Fixtures.json("hello-request.json").toString().toByteArray()))
        val expected = Fixtures.json("hello-response-granted.json")
        assertEquals(expected.getValue("grant"), answer.getValue("grant"))
        assertEquals(32, answer.getValue("nonce").jsonPrimitive.content.length)
        assertEquals(expected.getValue("nonceTtlMs"), answer.getValue("nonceTtlMs"))
    }

    @Test
    fun `hello never throws, and an unreadable request or a shared uid hears none`() {
        assertEquals("none", json(policy.hello(host, "not json".toByteArray())).getValue("grant").jsonObject.getValue("state").jsonPrimitive.content)
        val shared = host.copy(packages = listOf("com.example.a", "com.example.b"))
        val answer = json(policy.hello(shared, HelloRequest(wantNonce = true).toJson()))
        assertEquals(Fixtures.json("hello-response-none.json"), answer)
    }

    @Test
    fun `the nonce from hello is bound to the caller and the groups it named`() {
        val nonces = NonceBook(now = { clock }, randomBytes = { ByteArray(it) { 3 } })
        val withBook = ProviderPolicy(providerUid, label, records, servers, nonces) { clock }
        val nonce = json(withBook.hello(host, HelloRequest(wantNonce = true, groups = listOf("files")).toJson()))
            .getValue("nonce").jsonPrimitive.content
        assertNull("another app cannot use it", nonces.consume(nonce, uid = 10_999))
        val again = json(withBook.hello(host, HelloRequest(wantNonce = true, groups = listOf("files")).toJson()))
            .getValue("nonce").jsonPrimitive.content
        assertEquals(NonceBook.Binding(10_200, "com.example.host", "aa11", setOf("files")), nonces.consume(again, uid = 10_200))
    }

    @Test
    fun `an app without a record is told to connect first, naming the provider`() {
        val decision = policy.open(host, "main", openJson, channelsHeld = 0) as OpenDecision.Refused
        assertEquals(OpenStatus.NOT_GRANTED, decision.status)
        assertEquals(Fixtures.json("open-status-not-granted.json"), json(decision.status.toJson(decision.detail)))
    }

    @Test
    fun `a granted app opens a channel with its grant, and its last use is recorded`() {
        records.save(record())
        clock += 5_000
        val decision = policy.open(host, "main", openJson, channelsHeld = 0) as OpenDecision.Open
        assertEquals(filesOnly, decision.grant)
        assertEquals(clock, records.find("com.example.host")!!.lastUsedMs)
    }

    @Test
    fun `a grant unused for 90 days expires, so the app hears none and is told to connect first`() {
        records.save(record().copy(lastUsedMs = clock))
        clock += Atp.GRANT_UNUSED_MS
        assertTrue("on the last day it still opens", policy.open(host, "main", openJson, channelsHeld = 0) is OpenDecision.Open)

        clock += Atp.GRANT_UNUSED_MS + 1
        assertEquals("none", json(policy.hello(host, HelloRequest().toJson())).getValue("grant").jsonObject.getValue("state").jsonPrimitive.content)
        assertNull("the record is gone", records.find("com.example.host"))
        assertEquals(OpenStatus.NOT_GRANTED, (policy.open(host, "main", openJson, channelsHeld = 0) as OpenDecision.Refused).status)
    }

    @Test
    fun `a block never expires`() {
        records.save(record(blocked = true).copy(lastUsedMs = clock))
        clock += 10 * Atp.GRANT_UNUSED_MS
        assertEquals(OpenStatus.BLOCKED, (policy.open(host, "main", openJson, channelsHeld = 0) as OpenDecision.Refused).status)
    }

    @Test
    fun `expiring unused grants deletes only those past their time, and keeps blocks`() {
        records.save(HostRecord("com.example.old", "aa11", blocked = false, filesOnly, lastUsedMs = clock))
        records.save(HostRecord("com.example.blocked", "aa11", blocked = true, HostGrant(emptyMap()), lastUsedMs = clock))
        clock += Atp.GRANT_UNUSED_MS + 1
        records.save(HostRecord("com.example.recent", "aa11", blocked = false, filesOnly, lastUsedMs = clock))
        policy.expireUnused()
        assertEquals(listOf("com.example.blocked", "com.example.recent"), records.all().map { it.packageName })
    }

    @Test
    fun `each refusal carries the status the protocol names for it`() {
        records.save(record())
        assertEquals(OpenStatus.UNKNOWN_SERVER, (policy.open(host, "other", openJson, 0) as OpenDecision.Refused).status)
        val oldEra = OpenRequest(era = "2024-11-05").toJson()
        assertEquals(OpenStatus.UNSUPPORTED_VERSION, (policy.open(host, "main", oldEra, 0) as OpenDecision.Refused).status)
        assertEquals(OpenStatus.TOO_MANY_CHANNELS, (policy.open(host, "main", openJson, 8) as OpenDecision.Refused).status)
        val itself = host.copy(uid = providerUid)
        assertEquals(OpenStatus.IDENTITY_REFUSED, (policy.open(itself, "main", openJson, 0) as OpenDecision.Refused).status)
        records.save(record(blocked = true))
        assertEquals(OpenStatus.BLOCKED, (policy.open(host, "main", openJson, 0) as OpenDecision.Refused).status)
    }

    @Test
    fun `the eleventh open in a minute is refused, and a minute later opens work again`() {
        records.save(record())
        repeat(10) { assertTrue(policy.open(host, "main", openJson, 0) is OpenDecision.Open) }
        assertEquals(OpenStatus.RATE_LIMITED, (policy.open(host, "main", openJson, 0) as OpenDecision.Refused).status)
        clock += 61_000
        assertTrue(policy.open(host, "main", openJson, 0) is OpenDecision.Open)
    }

    @Test
    fun `an app signed by another key is a different app and must connect again`() {
        records.save(record(signer = "bb22"))
        assertEquals(OpenStatus.NOT_GRANTED, (policy.open(host, "main", openJson, 0) as OpenDecision.Refused).status)
        val answer = json(policy.hello(host, HelloRequest(wantNonce = true).toJson()))
        assertEquals("none", answer.getValue("grant").jsonObject.getValue("state").jsonPrimitive.content)
        assertTrue("it may ask the person again", "nonce" in answer)
    }

    @Test
    fun `a key the app rotated from is accepted and the record moves to the new key`() {
        records.save(record(signer = "old0"))
        val rotated = host.copy(pastSigners = listOf("old0"))
        assertTrue(policy.open(rotated, "main", openJson, 0) is OpenDecision.Open)
        assertEquals("aa11", records.find("com.example.host")!!.signer)
    }

    @Test
    fun `disconnecting deletes the caller's record and nobody else's`() {
        records.save(record())
        records.save(record().copy(packageName = "com.example.other"))
        policy.release(host)
        assertNull(records.find("com.example.host"))
        assertFalse(records.find("com.example.other") == null)
    }

    @Test
    fun `a group the person switches changes that group alone, and the next open serves it`() {
        records.save(record(grant = grant("files" to GroupState.ON, "exec" to GroupState.OFF)))
        assertTrue(policy.setGroup("com.example.host", "exec", GroupState.ON))

        val open = policy.open(host, "main", openJson, 0) as OpenDecision.Open
        assertEquals(grant("files" to GroupState.ON, "exec" to GroupState.ON), open.grant)
        assertFalse("an app with no record gets no grant from a switch", policy.setGroup("com.example.none", "exec", GroupState.ON))
        assertNull(records.find("com.example.none"))
    }

    @Test
    fun `a forgotten app, blocked or not, is asked about again`() {
        records.save(record(blocked = true))
        policy.forget("com.example.host")
        assertNull(records.find("com.example.host"))
        assertEquals(Fixtures.json("hello-response-none.json"), json(policy.hello(host, HelloRequest().toJson())))
    }

    @Test
    fun `a record survives being written and read back`() {
        val original = record(grant = grant("files" to GroupState.ON, "apk-install" to GroupState.ASK)).copy(lastUsedMs = 42)
        assertEquals(original, HostRecord.parse(original.toJson()))
        assertNull(HostRecord.parse("{\"package\":1}"))
    }

    @Test
    fun `a record keeps the app's name, and a record kept without one still reads`() {
        val named = record().copy(label = "Example Host")
        assertEquals(named, HostRecord.parse(named.toJson()))
        val kept = """{"package":"com.example.host","signer":"aa11","blocked":false,"lastUsedMs":0,""" +
            """"groups":{"files":"on","exec":"off","mcp":"off"}}"""
        assertEquals(record(), HostRecord.parse(kept))
    }

    @Test
    fun `a connected app's name is kept when it changes, and no record is made for an app never connected`() {
        records.save(record())
        policy.named("com.example.host", "Example Host")
        assertEquals("Example Host", records.find("com.example.host")?.label)
        assertEquals(filesOnly, records.find("com.example.host")?.grant)

        policy.named("com.example.none", "Nobody")
        assertNull(records.find("com.example.none"))
    }

    @Test
    fun `a stored record reads back with its groups, and a state this version does not know is left out`() {
        val kept = """{"package":"com.example.host","signer":"aa11","blocked":false,"lastUsedMs":7,""" +
            """"groups":{"files":"on","exec":"off","usb":"on","mcp":"sometimes"}}"""
        val record = HostRecord.parse(kept)!!
        assertEquals(grant("files" to GroupState.ON, "exec" to GroupState.OFF, "usb" to GroupState.ON), record.grant)
        assertEquals(7L, record.lastUsedMs)
    }

    @Test
    fun `a grant lists a tool whose group is on or asks, and runs it only when on`() {
        val asks = grant("files" to GroupState.ON, "exec" to GroupState.ASK, "mcp" to GroupState.OFF)
        assertTrue("a tool without a group is open to every connected app", asks.lists(null))
        assertTrue(asks.lists("files"))
        assertTrue(asks.lists("exec"))
        assertFalse(asks.lists("mcp"))
        assertFalse("a group the grant never named is off", asks.lists("usb"))
        assertEquals(emptyList<String>(), asks.missing("files"))
        assertEquals(listOf("exec"), asks.missing("exec"))
        assertEquals(emptyList<String>(), asks.missing(null))
    }
}
