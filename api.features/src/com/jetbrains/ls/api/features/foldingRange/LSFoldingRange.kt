// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.foldingRange

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FoldingRange
import com.jetbrains.lsp.protocol.FoldingRangeParams

object LSFoldingRange {
    val scope: Scope = Scope("lsp.foldingRange")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getFoldingRange(params: FoldingRangeParams): List<FoldingRange> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = FoldingRange.serializer(),
            providers = configuration.entriesFor<LSFoldingRangeProvider>(params.textDocument),
            getResults = { foldingRangeProvider ->
                tracer.traceProvider(
                    spanName = "provider.foldingRange",
                    provider = foldingRangeProvider,
                    resultsFlow = foldingRangeProvider.provideFoldingRanges(params),
                )
            },
        )
    }
}
