// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks a provider's `res/xml/atp_provider.xml` against SPEC.md 5.2: what breaks a rule, one sentence each, and nothing
 * when the file follows them all. A provider asserts in a plain unit test that its file passes, since a host that reads
 * a bad file treats the provider with more caution or not at all, and nothing tells the provider. A privacy policy or
 * contact that is missing, or at a reserved name such as example.com or a name under .invalid, is a problem: a host
 * shows those two links to the person, and a placeholder leads nowhere. The kit's own file is such a placeholder, with
 * neither, and every app replaces it with its own. A resource reference such as `@string/title` is not resolved, so only
 * a literal string's length is checked.
 */
object AtpProviderXml {

    fun problems(xml: String): List<String> {
        val root = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        } catch (unreadable: SAXException) {
            return listOf("The file is not well-formed XML: ${unreadable.message}")
        }
        val found = mutableListOf<String>()
        if (xml.toByteArray(Charsets.UTF_8).size > MAX_BYTES) found += "The file is over ${MAX_BYTES / 1024} KB."
        if (root.localName != "atp-provider") return listOf("The root element is <${root.localName}>, not <atp-provider>.")
        if (root.getAttribute("spec") != "1") found += "spec must be \"1\"."
        found += listOfNotNull(
            address("privacyPolicy", root.getAttribute("privacyPolicy"), "https:"),
            address("contact", root.getAttribute("contact"), "https:", "mailto:"),
        )

        val servers = children(root, "server")
        val groups = children(root, "group")
        if (servers.isEmpty()) found += "There is no <server>."
        if (servers.size > MAX_SERVERS) found += "There are ${servers.size} servers; at most $MAX_SERVERS are allowed."
        if (groups.size > MAX_GROUPS) found += "There are ${groups.size} groups; at most $MAX_GROUPS are allowed."
        found += ids("server", servers) + ids("group", groups)

        servers.forEach { server ->
            val id = server.getAttribute("id")
            if (ERA !in words(server, "mcpEras")) found += "Server $id: mcpEras must include $ERA."
            words(server, "toolFamily").filter { it !in FAMILIES }.forEach { found += "Server $id: toolFamily \"$it\" is not one of ${FAMILIES.joinToString()}." }
            found += lengths("Server $id", server)
        }
        groups.forEach { group ->
            val id = group.getAttribute("id")
            if (group.getAttribute("risk") !in RISKS) found += "Group $id: risk must be one of ${RISKS.joinToString()}."
            val kinds = words(group, "kind")
            if (kinds.isEmpty()) found += "Group $id: no kind."
            kinds.filter { it !in KINDS }.forEach { found += "Group $id: kind \"$it\" is not one of ${KINDS.joinToString()}." }
            if (group.getAttribute("grant") !in GRANTS) found += "Group $id: grant must be in-flow or provider-ui."
            found += lengths("Group $id", group)
        }
        return found
    }

    /** What is wrong with [url], the value of [attribute]: missing, not a URL of one of [schemes], or a placeholder. */
    private fun address(attribute: String, url: String, vararg schemes: String): String? {
        if (url.isBlank()) return "$attribute is missing."
        val hosts = hostsOf(url)
        if (schemes.none { url.startsWith(it) } || hosts.isEmpty() || hosts.any { it.isEmpty() }) {
            return "$attribute must be an ${schemes.joinToString(" or ")} URL."
        }
        val reserved = hosts.firstOrNull { isReserved(it) } ?: return null
        return "$attribute is a placeholder: $reserved is a reserved name (RFC 2606, RFC 6761)."
    }

    /** The names [url] points at: an https: URL's host, or the domain of each address in a mailto: URL. */
    private fun hostsOf(url: String): List<String> = when {
        url.startsWith("https:") -> {
            val authority = url.removePrefix("https:").trimStart('/').substringBefore('/').substringBefore('?').substringBefore('#')
            val host = authority.substringAfterLast('@')
            listOf(if (host.startsWith('[')) host.substringBefore(']') + ']' else host.substringBefore(':'))
        }
        url.startsWith("mailto:") -> url.removePrefix("mailto:").substringBefore('?').split(',').map { it.substringAfterLast('@', "") }
        else -> emptyList<String>()
    }.map { it.trim().trimEnd('.').lowercase() }

    private fun isReserved(host: String): Boolean = RESERVED.any { host == it || host.endsWith(".$it") }

    private fun children(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun ids(what: String, elements: List<Element>): List<String> {
        val ids = elements.map { it.getAttribute("id") }
        val bad = ids.filterNot(ID::matches).map { "A $what's id \"$it\" must be 1 to 32 of a-z, 0-9 and -." }
        val repeated = ids.groupBy { it }.filter { it.value.size > 1 }.keys.map { "The $what id \"$it\" is used twice." }
        return bad + repeated
    }

    private fun words(element: Element, attribute: String): List<String> =
        element.getAttribute(attribute).split(' ').filter { it.isNotBlank() }

    private fun lengths(what: String, element: Element): List<String> = buildList {
        val title = element.getAttributeNS(ANDROID, "title")
        val summary = element.getAttributeNS(ANDROID, "summary")
        if (!title.startsWith("@") && title.length > MAX_STRING) add("$what: title is over $MAX_STRING characters.")
        if (!summary.startsWith("@") && summary.length > MAX_SUMMARY) add("$what: summary is over $MAX_SUMMARY characters.")
    }

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val ERA = "2025-06-18"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_SERVERS = 16
    private const val MAX_GROUPS = 32
    private const val MAX_STRING = 200
    private const val MAX_SUMMARY = 80
    private val ID = Regex("[a-z0-9-]{1,32}")
    private val FAMILIES = listOf("files", "code", "device", "web", "media", "productivity")
    private val RISKS = listOf("low", "medium", "high", "critical")
    private val KINDS = listOf("read", "write", "exec", "egress", "install", "device")
    private val GRANTS = listOf("in-flow", "provider-ui")

    /**
     * Names reserved for examples, tests and addresses that never work (RFC 2606, RFC 6761), each with every name under
     * it, so no real site or mailbox has one.
     */
    private val RESERVED = listOf("example", "example.com", "example.net", "example.org", "invalid", "localhost", "test")
}
