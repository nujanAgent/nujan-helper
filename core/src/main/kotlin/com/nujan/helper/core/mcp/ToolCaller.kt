package com.nujan.helper.core.mcp

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The connected app a tool call came from, carried in the call's coroutine context, so what the call starts belongs
 * to that app and what it lists is that app's own. [pinned] is true for an app the Helper trusts by publisher, checked
 * against its signing certificate when its channel opened. [turn] is the turn of the app's agent the call belongs to,
 * when the app names one, so Stop can end that turn alone.
 */
class ToolCaller(val packageName: String, val pinned: Boolean = false, val turn: String? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolCaller>
}

/** The package of the app whose call is running, or null for work the Helper does itself. */
suspend fun callingApp(): String? = currentCoroutineContext()[ToolCaller]?.packageName

/** The app whose call is running, or null for work the Helper does itself. */
suspend fun toolCaller(): ToolCaller? = currentCoroutineContext()[ToolCaller]
