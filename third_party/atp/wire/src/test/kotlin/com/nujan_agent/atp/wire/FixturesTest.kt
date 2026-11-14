// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code's tables and the golden fixtures say the same thing, value for value. */
class FixturesTest {

    @Test
    fun `every open status in the fixture table is the code's`() {
        val table = Fixtures.json("open-status-codes.json").jsonArray.map { it.jsonObject }
        assertEquals(OpenStatus.entries.size, table.size)
        for (row in table) {
            val status = OpenStatus.of(row["status"]!!.jsonPrimitive.int)
            assertEquals(row["name"]!!.jsonPrimitive.content, status?.name)
        }
    }

    @Test
    fun `every close reason in the fixture table is the code's`() {
        val table = Fixtures.json("close-reasons.json").jsonArray.map { it.jsonObject }
        assertEquals(CloseReason.entries.size, table.size)
        for (row in table) {
            assertEquals(row["name"]!!.jsonPrimitive.content, CloseReason.of(row["reason"]!!.jsonPrimitive.int)?.name)
        }
    }

    @Test
    fun `the error codes are the code's and outside the range JSON-RPC reserves`() {
        val table = Fixtures.json("errors.json").jsonArray.map { it.jsonObject }
        assertEquals(AtpError.entries.map { it.code to it.name }, table.map { it["code"]!!.jsonPrimitive.int to it["name"]!!.jsonPrimitive.content })
        assertTrue(AtpError.entries.none { it.code in -32768..-32000 })
    }

    @Test
    fun `status JSON is written as the fixtures have it`() {
        assertEquals(OpenResult(0, OpenStatus.OK, ""), OpenResult.parse(Fixtures.bytes("open-status-ok.json")))
        val refused = OpenResult.parse(Fixtures.bytes("open-status-not-granted.json"))
        assertEquals(OpenStatus.NOT_GRANTED, refused.status)
        assertEquals(refused, OpenResult.parse(OpenStatus.NOT_GRANTED.toJson(refused.detail)))
        assertEquals(OpenResult(0, OpenStatus.OK, ""), OpenResult.parse(OpenStatus.OK.toJson()))
    }

    @Test
    fun `a status code this version does not know is not OK`() {
        val unknown = OpenResult.parse("""{"status":99,"name":"LATER","detail":"x"}""".toByteArray())
        assertEquals(99, unknown.code)
        assertEquals(null, unknown.status)
        assertFalse(unknown.ok)
        assertFalse(OpenResult.parse("not json".toByteArray()).ok)
    }

    @Test
    fun `the close detail reads back its reason`() {
        val detail = Fixtures.json("close-detail-revoked.json").jsonObject
        assertEquals(CloseReason.REVOKED.value, detail["reason"]!!.jsonPrimitive.int)
        val written = WireJson.objectOf(CloseReason.REVOKED.detailJson(detail["detail"]!!.jsonPrimitive.content))!!
        assertEquals(detail, written)
    }

    @Test
    fun `the consent extras and limits are the code's`() {
        val consent = Fixtures.json("consent.json").jsonObject
        assertEquals(Atp.ACTION_CONNECT, consent.text("actionConnect"))
        assertEquals(Atp.EXTRA_NONCE, consent.text("extraNonce"))
        assertEquals(Atp.EXTRA_GROUPS, consent.text("extraGroups"))
        assertEquals(Atp.EXTRA_PURPOSE, consent.text("extraPurpose"))
        assertEquals(Atp.EXTRA_OUTCOME, consent.text("extraOutcome"))
        assertEquals(Atp.CONSENT_RESULT_CODE, consent["resultCode"]!!.jsonPrimitive.int)
        assertEquals(ConsentOutcome.entries.map { it.wire }, consent["outcomes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Atp.PURPOSE_MAX_CHARS, consent["purposeMaxChars"]!!.jsonPrimitive.int)
        assertEquals(Atp.NONCE_HEX_CHARS, consent["nonceHexChars"]!!.jsonPrimitive.int)
        assertEquals(Atp.NONCE_TTL_MS, consent["nonceTtlMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the names are the code's`() {
        val names = Fixtures.json("names.json").jsonObject
        assertEquals(Atp.AIDL_PACKAGE, names.text("aidlPackage"))
        assertEquals(Atp.ACTION_PROVIDER, names.text("actionProvider"))
        assertEquals(Atp.ACTION_CONNECT, names.text("actionConnect"))
        assertEquals(Atp.ACTION_REQUEST_CONNECT, names.text("actionRequestConnect"))
        assertEquals(Atp.ACTION_CONNECTED_APPS, names.text("actionConnectedApps"))
        assertEquals(Atp.META_DATA_PROVIDER, names.text("metaDataProvider"))
        assertEquals(Atp.MANIFEST_ROOT, names.text("manifestRoot"))
        assertEquals(Atp.META_GROUP, names.text("metaKeyGroup"))
        assertEquals(Atp.META_ORIGIN, names.text("metaKeyOrigin"))
        assertEquals(Atp.META_EPHEMERAL, names.text("metaKeyEphemeral"))
        assertEquals(Atp.META_MISSING_GROUPS, names.text("metaKeyMissingGroups"))
        assertEquals(Atp.META_ASKING, names.text("metaKeyAsking"))
        assertEquals(Atp.META_TURN, names.text("metaKeyTurn"))
        assertEquals(Atp.META_STOPPED, names.text("metaKeyStopped"))
        assertEquals(Atp.TURN_ENDED, names.text("methodTurnEnded"))
        assertEquals(Atp.TURN_STOPPED, names.text("methodTurnStopped"))
    }

    @Test
    fun `hello requests and responses read as the fixtures say`() {
        val request = HelloRequest.parse(Fixtures.bytes("hello-request.json"))!!
        assertEquals(HelloRequest(wantNonce = true, groups = listOf("files", "exec")), request)
        assertEquals(request, HelloRequest.parse(request.toJson()))

        val granted = HelloResponse.parse(Fixtures.bytes("hello-response-granted.json"))!!
        assertEquals(GrantView.State.GRANTED, granted.grant.state)
        assertEquals(mapOf("files" to GroupState.ON, "exec" to GroupState.ASK, "mcp" to GroupState.OFF), granted.grant.groups)
        assertEquals(listOf(ServerInfo("main", listOf(Atp.ERA))), granted.servers)
        assertEquals(Limits(), granted.limits)
        assertEquals(Atp.NONCE_HEX_CHARS, granted.nonce!!.length)
        assertEquals(Atp.NONCE_TTL_MS, granted.nonceTtlMs)
        assertEquals(granted, HelloResponse.parse(granted.toJson()))

        assertEquals(GrantView.State.NONE, HelloResponse.parse(Fixtures.bytes("hello-response-none.json"))!!.grant.state)
        assertEquals(GrantView.State.BLOCKED, HelloResponse.parse(Fixtures.bytes("hello-response-blocked.json"))!!.grant.state)
        assertEquals(null, HelloResponse.parse(Fixtures.bytes("hello-response-none.json"))!!.nonce)
    }

    @Test
    fun `a hello whose fields have the wrong types is read without throwing`() {
        val odd = """{"binding":{"x":1},"grant":{"state":"granted","groups":{"files":7}},"servers":"main"}"""
        val parsed = HelloResponse.parse(odd.toByteArray())!!
        assertEquals(Atp.BINDING_VERSION, parsed.binding)
        assertEquals(emptyMap<String, GroupState>(), parsed.grant.groups)
        assertEquals(emptyList<ServerInfo>(), parsed.servers)
        assertEquals(null, HelloResponse.parse("""{"grant":{"state":"maybe"}}""".toByteArray()))
    }

    @Test
    fun `the open request is v0 and anything else is unsupported`() {
        val open = OpenRequest.parse(Fixtures.bytes("open-request.json"))!!
        assertEquals(OpenRequest(), open)
        assertTrue(open.supported)
        assertEquals(open, OpenRequest.parse(open.toJson()))
        assertFalse(open.copy(framing = "http").supported)
        assertFalse(open.copy(era = "2026-07-28").supported)
        assertFalse(open.copy(binding = 2).supported)
        assertFalse(open.copy(spec = "1.0").supported)
        assertFalse(OpenRequest.parse("{}".toByteArray())!!.supported)
    }

    @Test
    fun `a tool's group is in its meta, and a tool without one is implicit`() {
        val tools = Fixtures.json("tools-list-result.json").jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        val groups = tools.associate { tool ->
            val obj = tool.jsonObject
            obj.text("name") to (obj["_meta"] as? JsonObject)?.get(Atp.META_GROUP)?.jsonPrimitive?.content
        }
        assertEquals(mapOf("echo" to null, "fs_read" to "files", "run_command" to "exec", "mcp_everything_echo" to "mcp"), groups)
    }

    @Test
    fun `a call the host is not granted is an error result naming the missing groups`() {
        val result = Fixtures.json("ungranted-call-result.json").jsonObject["result"]!!.jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        val missing = (result["_meta"] as JsonObject)[Atp.META_MISSING_GROUPS] as JsonArray
        assertEquals(listOf("exec"), missing.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the AIDL table numbers each interface's methods from one in declaration order`() {
        val table = Fixtures.json("aidl-table.json").jsonObject
        val provider = table["IAtpProvider"]!!.jsonObject
        assertEquals("${Atp.AIDL_PACKAGE}.IAtpProvider", provider.text("descriptor"))
        assertEquals(
            listOf("hello", "openChannel", "closeChannel", "uiResult", "releaseGrant"),
            provider["transactions"]!!.jsonObject.entries.sortedBy { it.value.jsonPrimitive.int }.map { it.key },
        )
        val host = table["IAtpHost"]!!.jsonObject
        assertEquals("${Atp.AIDL_PACKAGE}.IAtpHost", host.text("descriptor"))
        assertEquals((1..3).toList(), host["transactions"]!!.jsonObject.values.map { it.jsonPrimitive.int }.sorted())
    }

    /** A fixture added without a test that reads it, or removed while one still expects it, fails here. */
    @Test
    fun `the fixtures are exactly the set these tests read`() {
        assertNotNull(Fixtures.names())
        assertEquals(
            listOf(
                "aidl-table.json", "close-detail-revoked.json", "close-reasons.json", "consent.json", "errors.json",
                "hello-request.json", "hello-response-blocked.json", "hello-response-granted.json",
                "hello-response-none.json", "names.json", "open-request.json", "open-status-codes.json",
                "open-status-not-granted.json", "open-status-ok.json", "tools-list-result.json",
                "ungranted-call-result.json",
            ),
            Fixtures.names(),
        )
    }

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content
}
