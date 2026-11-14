// SPDX-License-Identifier: Apache-2.0
package com.nujan_agent.atp.provider.internal

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import com.nujan_agent.atp.provider.R
import org.xmlpull.v1.XmlPullParser

/** A group as the app's provider XML declares it (SPEC.md 5.2, 6.4). */
internal data class GroupDeclaration(
    val id: String,
    val title: String,
    val summary: String,
    /** `in-flow` when the connect screen grants it, `provider-ui` when only the app's own screen may turn it on. */
    val grant: String,
    val risk: String,
)

/** A server as the app's provider XML declares it. */
internal data class ServerDeclaration(
    val id: String,
    val mcpEras: List<String>,
    /** The class of the app's tool source, when the XML names one. */
    val entry: String?,
)

/** What the app's `res/xml/atp_provider.xml` says, the same file hosts read before they bind (SPEC.md 5.2). */
internal data class ProviderManifest(val servers: List<ServerDeclaration>, val groups: List<GroupDeclaration>) {

    fun group(id: String): GroupDeclaration? = groups.firstOrNull { it.id == id }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"

        fun read(context: Context): ProviderManifest = context.resources.getXml(R.xml.atp_provider).use { parse(it, context.resources) }

        private fun parse(parser: XmlResourceParser, resources: Resources): ProviderManifest {
            val servers = mutableListOf<ServerDeclaration>()
            val groups = mutableListOf<GroupDeclaration>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val id = parser.getAttributeValue(null, "id") ?: continue
                when (parser.name) {
                    "server" -> servers += ServerDeclaration(
                        id = id,
                        mcpEras = parser.getAttributeValue(null, "mcpEras")?.split(' ')?.filter(String::isNotBlank).orEmpty(),
                        entry = parser.getAttributeValue(null, "entry"),
                    )
                    "group" -> groups += GroupDeclaration(
                        id = id,
                        title = text(parser, resources, "title") ?: id,
                        summary = text(parser, resources, "summary") ?: "",
                        grant = parser.getAttributeValue(null, "grant") ?: "in-flow",
                        risk = parser.getAttributeValue(null, "risk") ?: "low",
                    )
                }
            }
            return ProviderManifest(servers, groups)
        }

        private fun text(parser: XmlResourceParser, resources: Resources, name: String): String? {
            val id = parser.getAttributeResourceValue(ANDROID, name, 0)
            return if (id != 0) resources.getString(id) else parser.getAttributeValue(ANDROID, name)
        }
    }
}
