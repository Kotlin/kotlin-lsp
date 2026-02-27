// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.diagnostic.telemetry.IJTracer
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.single

/**
 * Wraps the provider result collection into a provider-level span.
 *
 * This helper propagates the created span through coroutine context,
 * records non-cancellation failures on the span,
 * and always closes the span when the provider flow completes.
 */
internal fun <T> IJTracer.traceProvider(
    spanName: String,
    provider: Any,
    resultsFlow: Flow<T>,
): Flow<T> = flow {
    val span = spanBuilder(spanName)
        .setAttribute("provider.class", provider.javaClass.simpleName)
        .startSpan()
    try {
        emitAll(resultsFlow.flowOn(Context.current().with(span).asContextElement()))
    } catch (e: Throwable) {
        if (Logger.shouldRethrow(e)) throw e
        span.recordException(e)
        span.setStatus(StatusCode.ERROR, e.message ?: e.javaClass.simpleName)
        throw e
    } finally {
        span.end()
    }
}

internal suspend fun <T> IJTracer.traceProvider(
    spanName: String,
    provider: Any,
    block: suspend () -> T,
): T {
    return traceProvider(
        spanName = spanName,
        provider = provider,
        resultsFlow = flow { emit(block()) },
    ).single()
}
