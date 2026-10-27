// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.partialResults

import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.streamResultsIfPossibleOrRespondDirectly
import com.jetbrains.lsp.protocol.ProgressToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer

internal object LSConcurrentResponseHandler {
    suspend fun <H, R> respondDirectlyWithResultsCollectedConcurrently(
        providers: List<H>,
        getResults: (H) -> Flow<R>
    ): List<R> {
        if (providers.isEmpty()) return emptyList()
        return providers.map { getResults(it) }.concurrentMerge().toList()
    }


    /**
     * @param listingOrder groups the providers: the results of a group with a lower order go first. The providers of one
     *   group give their results concurrently, in any order.
     * @param arrange sorts or trims all the results before any is sent, so nothing streams before the providers finish.
     */
    context(handlerContext: LspHandlerContext)
    suspend fun <H, R> streamResultsIfPossibleOrRespondDirectly(
        partialResultToken: ProgressToken?,
        resultSerializer: KSerializer<R>,
        providers: List<H>,
        listingOrder: (H) -> Int = { 0 },
        arrange: ((List<R>) -> List<R>)? = null,
        getResults: (H) -> Flow<R>
    ): List<R> {
        if (providers.isEmpty()) return emptyList()
        val merged = providers.groupBy(listingOrder).toSortedMap().values
            .map { group -> group.map { getResults(it) }.concurrentMerge() }
            .reduce { results, laterResults -> results.followedBy(laterResults) }
        val arranged = if (arrange == null) merged else flow { emitAll(arrange(merged.toList()).asFlow()) }
        return arranged.streamResultsIfPossibleOrRespondDirectly(lspClient, partialResultToken, resultSerializer)

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <R> List<Flow<R>>.concurrentMerge(): Flow<R> = asFlow().flattenMerge()

    /** The results of [this], then those of [last]. Both are collected concurrently, [last] is held back until [this] completes. */
    private fun <R> Flow<R>.followedBy(last: Flow<R>): Flow<R> = channelFlow {
        val lastResults = last.buffer(Channel.UNLIMITED).produceIn(this)
        collect { send(it) }
        lastResults.consumeEach { send(it) }
    }
}