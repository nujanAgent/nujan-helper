// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SPEC.md 5.2's rules for a provider's XML: the spec's own example passes once its addresses are a provider's own, each
 * broken rule is named, and a privacy policy or contact that is missing or a placeholder is named too.
 */
class AtpProviderXmlTest {

    private val example = """
        <atp-provider xmlns:android="http://schemas.android.com/apk/res/android"
            spec="1" privacyPolicy="https://example.com/privacy" contact="mailto:dev@example.com">
          <server id="main" mcpEras="2025-06-18" toolFamily="code files" android:title="@string/server_title" />
          <group id="files" risk="low" kind="read write" grant="in-flow"
                 android:title="@string/g_files" android:summary="@string/g_files_s" />
          <group id="exec" risk="critical" kind="exec egress" grant="provider-ui"
                 android:title="@string/g_exec" android:summary="@string/g_exec_s" />
        </atp-provider>
    """.trimIndent()

    private val ownPolicy = "https://www.provider.app/privacy"
    private val ownContact = "mailto:support@provider.app"

    /** The spec's example with [privacyPolicy] and [contact] in place of its addresses; null leaves the attribute out. */
    private fun withAddresses(privacyPolicy: String?, contact: String?) = example
        .replace("privacyPolicy=\"https://example.com/privacy\"", privacyPolicy?.let { "privacyPolicy=\"$it\"" }.orEmpty())
        .replace("contact=\"mailto:dev@example.com\"", contact?.let { "contact=\"$it\"" }.orEmpty())

    private fun problems(privacyPolicy: String?, contact: String?) = AtpProviderXml.problems(withAddresses(privacyPolicy, contact))

    private fun placeholder(attribute: String, host: String) = "$attribute is a placeholder: $host is a reserved name (RFC 2606, RFC 6761)."

    @Test
    fun `the spec's example passes once its addresses are the provider's own`() {
        assertEquals(emptyList<String>(), problems(ownPolicy, ownContact))
        // An example's addresses are reserved names, which a shipped file never has.
        assertEquals(
            listOf(placeholder("privacyPolicy", "example.com"), placeholder("contact", "example.com")),
            AtpProviderXml.problems(example),
        )
    }

    @Test
    fun `each broken rule is named`() {
        val broken = """
            <atp-provider xmlns:android="http://schemas.android.com/apk/res/android"
                spec="2" contact="http://example.com">
              <server id="Main" mcpEras="2024-11-05" toolFamily="code games" />
              <group id="files" risk="severe" kind="read delete" grant="anywhere"
                     android:title="Files" android:summary="${"x".repeat(81)}" />
              <group id="files" risk="low" grant="in-flow" />
            </atp-provider>
        """.trimIndent()

        assertEquals(
            listOf(
                "spec must be \"1\".",
                "privacyPolicy is missing.",
                "contact must be an https: or mailto: URL.",
                "A server's id \"Main\" must be 1 to 32 of a-z, 0-9 and -.",
                "The group id \"files\" is used twice.",
                "Server Main: mcpEras must include 2025-06-18.",
                "Server Main: toolFamily \"games\" is not one of files, code, device, web, media, productivity.",
                "Group files: risk must be one of low, medium, high, critical.",
                "Group files: kind \"delete\" is not one of read, write, exec, egress, install, device.",
                "Group files: grant must be in-flow or provider-ui.",
                "Group files: summary is over 80 characters.",
                "Group files: no kind.",
            ),
            AtpProviderXml.problems(broken),
        )
    }

    /** SPEC.md 5.2: both are required, and a host shows both to the person before it connects. */
    @Test
    fun `a missing or blank privacy policy or contact is named`() {
        assertEquals(listOf("privacyPolicy is missing.", "contact is missing."), problems(null, null))
        assertEquals(listOf("privacyPolicy is missing.", "contact is missing."), problems("", " "))
    }

    @Test
    fun `an address of another kind, or with no host, is named`() {
        val wrong = listOf("privacyPolicy must be an https: URL.", "contact must be an https: or mailto: URL.")
        assertEquals(wrong, problems("http://www.provider.app/privacy", "tel:+351210000000"))
        assertEquals(wrong, problems("https://", "mailto:"))
        assertEquals(wrong, problems("mailto:support@provider.app", "mailto:support"))
    }

    @Test
    fun `a privacy policy or contact at a reserved name is a placeholder`() {
        val reserved = mapOf(
            "https://gdatp.invalid/privacy" to "gdatp.invalid",
            "https://example.com/privacy" to "example.com",
            "https://www.EXAMPLE.org./privacy" to "www.example.org",
            "https://docs.example.net:8443/privacy" to "docs.example.net",
            "https://help.example/privacy" to "help.example",
            "https://someone@shop.test/privacy" to "shop.test",
            "https://localhost/privacy" to "localhost",
            "https://app.localhost:8080/privacy" to "app.localhost",
        )
        reserved.forEach { (url, host) ->
            assertEquals(url, listOf(placeholder("privacyPolicy", host)), problems(url, ownContact))
            assertEquals(url, listOf(placeholder("contact", host)), problems(ownPolicy, url))
        }
        assertEquals(listOf(placeholder("contact", "gdatp.invalid")), problems(ownPolicy, "mailto:gdatp@gdatp.invalid"))
        assertEquals(
            "one placeholder among several addresses is enough",
            listOf(placeholder("contact", "example.com")),
            problems(ownPolicy, "mailto:support@provider.app,dev@example.com?subject=ATP"),
        )
    }

    @Test
    fun `names that only look reserved pass`() {
        listOf(
            "https://example.co/privacy" to "mailto:help@notexample.com",
            "https://example.com.au/privacy" to "mailto:help@testing.dev",
            "https://invalid-ideas.org/privacy" to "https://localhost.app/contact",
            "https://www.provider.app/privacy?from=example.com#test" to "mailto:help@provider.app?subject=example.com",
        ).forEach { (policy, contact) ->
            assertEquals("$policy, $contact", emptyList<String>(), problems(policy, contact))
        }
    }

    /** The kit's own file stands in until an app supplies its own, and the check names what makes it unfit to ship. */
    @Test
    fun `the kit's placeholder file is reported`() {
        assertEquals(
            listOf("privacyPolicy is missing.", "contact is missing."),
            AtpProviderXml.problems(File("src/main/res/xml/atp_provider.xml").readText()),
        )
    }

    @Test
    fun `a file with no server, or that is not this file at all, says so`() {
        val noServer = withAddresses(ownPolicy, ownContact).lines().filterNot { "<server" in it }.joinToString("\n")
        assertEquals(listOf("There is no <server>."), AtpProviderXml.problems(noServer))
        assertEquals(listOf("The root element is <provider>, not <atp-provider>."), AtpProviderXml.problems("<provider/>"))
        assertTrue(AtpProviderXml.problems("<atp-provider").single().startsWith("The file is not well-formed XML"))
    }
}
