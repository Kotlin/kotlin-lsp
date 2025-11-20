// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.annotations.ApiStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@ApiStatus.Internal
suspend inline fun <T> retryWithBackOff(
    timeout: Duration = 30.seconds,
    onError: (Exception, Duration) -> Unit = { _, _ -> },
    block: suspend () -> T
): T {
    var backoff = 2.seconds
    var timeLeft = timeout
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (timeLeft <= Duration.ZERO) throw e
            onError(e, backoff)
            val delayTime = backoff.coerceAtMost(timeLeft)
            delay(delayTime)
            timeLeft -= delayTime
            backoff = (backoff * 2).coerceAtMost(timeout / 2)
        }
    }
}