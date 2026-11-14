package com.nujan.helper.atp

import com.nujan_agent.atp.provider.AtpUi
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** How the running call brings up the Helper's own screens (SPEC.md 9.1), carried in the call's coroutine context. */
class CallUi(val ui: AtpUi) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallUi>
}

/** The running call's way to show the Helper's screens; outside a call nothing can be shown. */
suspend fun callUi(): AtpUi = currentCoroutineContext()[CallUi]?.ui ?: AtpUi.NONE
