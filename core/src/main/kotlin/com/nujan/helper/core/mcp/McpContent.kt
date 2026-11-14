package com.nujan.helper.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Base64

/**
 * A hosted server's `tools/call` result as the Helper passes it on. Every text block is kept, in order; pictures stay
 * pictures, and since they travel after the text, a `[picture N]` line marks where each one was, so text such as "the
 * image above" still reads right; a link or an embedded text resource becomes text that names it; and what cannot be
 * passed on (audio, a binary resource, a type this reader does not know) becomes a line saying so, so the model is told
 * what was left out rather than handed less than the server sent.
 */
object McpContent {

    fun read(result: JsonObject): RemoteToolResult {
        val parts = mutableListOf<String>()
        val images = mutableListOf<ToolImage>()
        (result["content"] as? JsonArray).orEmpty().forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            when (val type = block.text("type")) {
                "text" -> block.text("text")?.let(parts::add)
                "image" -> picture(block.text("data"), block.text("mimeType"))
                    ?.let { images.add(it); parts.add("[picture ${images.size}]") }
                    ?: parts.add("[a picture that could not be read]")
                "audio" -> parts.add("[audio (${block.text("mimeType") ?: "no type given"}) left out: the Helper passes on text and pictures]")
                "resource_link" -> parts.add(link(block))
                "resource" -> embedded(block["resource"] as? JsonObject, parts, images)
                else -> parts.add("[content of type ${type ?: "unnamed"} left out]")
            }
        }
        val structured = result["structuredContent"] as? JsonObject
        // A result may give its answer only as structured content; the model then reads it as JSON text.
        if (parts.isEmpty() && images.isEmpty() && structured != null) parts.add(structured.toString())
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return RemoteToolResult(parts.joinToString("\n\n"), isError, structured, images)
    }

    private fun link(block: JsonObject): String {
        val uri = block.text("uri") ?: "no address given"
        val name = block.text("title") ?: block.text("name") ?: uri
        return "Link: $name, $uri" + block.text("description")?.let { "\n$it" }.orEmpty()
    }

    private fun embedded(resource: JsonObject?, parts: MutableList<String>, images: MutableList<ToolImage>) {
        val uri = resource?.text("uri") ?: "a resource with no address"
        val mimeType = resource?.text("mimeType")
        val text = resource?.text("text")
        val blob = resource?.text("blob")
        when {
            text != null -> parts.add("$uri:\n$text")
            blob != null && mimeType?.startsWith("image/") == true ->
                picture(blob, mimeType)?.let { images.add(it); parts.add("[picture ${images.size}: $uri]") }
                    ?: parts.add("[a picture at $uri that could not be read]")
            else -> parts.add("[$uri (${mimeType ?: "no type given"}) left out: the Helper passes on text and pictures]")
        }
    }

    /** A base64 picture, or null when it is not one: no image type, or data that does not decode. */
    private fun picture(data: String?, mimeType: String?): ToolImage? {
        if (data == null || mimeType?.startsWith("image/") != true) return null
        return try {
            ToolImage(mimeType, Base64.getDecoder().decode(data))
        } catch (notBase64: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
