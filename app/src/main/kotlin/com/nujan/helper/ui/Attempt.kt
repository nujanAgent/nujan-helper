package com.nujan.helper.ui

import kotlin.coroutines.cancellation.CancellationException

/** [block]'s value or its failure; a cancelled coroutine stays cancelled rather than being reported as a failure. */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
