// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.partialResults

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.streamResultsIfPossibleOrRespondDirectly
import com.jetbrains.lsp.protocol.ProgressToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer

internal object LSConcurrentResponseHandler {
    context(_: LspHandlerContext, _: LSServer)
    suspend fun <H, R> respondDirectlyWithResultsCollectedConcurrently(
        providers: List<H>,
        getResults: (H) -> Flow<R>
    ): List<R> {
        if (providers.isEmpty()) return emptyList()
        return providers.map { getResults(it) }.concurrentMerge().toList()
    }


    context(_: LspHandlerContext, _: LSServer)
    suspend fun <H, R> streamResultsIfPossibleOrRespondDirectly(
        partialResultToken: ProgressToken?,
        resultSerializer: KSerializer<R>,
        providers: List<H>,
        getResults: (H) -> Flow<R>
    ): List<R> {
        if (providers.isEmpty()) return emptyList()
        return providers
            .map { getResults(it) }
            .concurrentMerge()
            .streamResultsIfPossibleOrRespondDirectly(lspClient, partialResultToken, resultSerializer)

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <R> List<Flow<R>>.concurrentMerge(): Flow<R> = asFlow().flattenMerge()
}