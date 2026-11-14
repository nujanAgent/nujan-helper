package com.nujan_agent.atp.v1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The stubs the AIDL compiler generates for the Helper carry the descriptors and transaction codes the ATP fixture
 * table pins, so the Helper and every host agree on the wire without sharing a binary. In the AIDL package because
 * the generated `TRANSACTION_` constants are package-private.
 */
class AidlDescriptorTest {

    private val table = Json.parseToJsonElement(File("../third_party/atp/fixtures/aidl-table.json").readText()).jsonObject

    @Test
    fun `the provider interface matches the fixture table`() {
        val provider = table.getValue("IAtpProvider").jsonObject
        assertEquals(provider.getValue("descriptor").jsonPrimitive.content, IAtpProvider.DESCRIPTOR)
        assertEquals(
            provider.getValue("transactions").jsonObject.mapValues { it.value.jsonPrimitive.int },
            mapOf(
                "hello" to IAtpProvider.Stub.TRANSACTION_hello,
                "openChannel" to IAtpProvider.Stub.TRANSACTION_openChannel,
                "closeChannel" to IAtpProvider.Stub.TRANSACTION_closeChannel,
                "uiResult" to IAtpProvider.Stub.TRANSACTION_uiResult,
                "releaseGrant" to IAtpProvider.Stub.TRANSACTION_releaseGrant,
            ),
        )
    }

    @Test
    fun `the host interface matches the fixture table`() {
        val host = table.getValue("IAtpHost").jsonObject
        assertEquals(host.getValue("descriptor").jsonPrimitive.content, IAtpHost.DESCRIPTOR)
        assertEquals(
            host.getValue("transactions").jsonObject.mapValues { it.value.jsonPrimitive.int },
            mapOf(
                "onUiRequest" to IAtpHost.Stub.TRANSACTION_onUiRequest,
                "onClosed" to IAtpHost.Stub.TRANSACTION_onClosed,
                "onServersChanged" to IAtpHost.Stub.TRANSACTION_onServersChanged,
            ),
        )
    }
}
