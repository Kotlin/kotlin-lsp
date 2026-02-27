// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProviderResults
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolParams

object LSDocumentSymbols {
    val scope: Scope = Scope("lsp.documentSymbols")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getSymbols(params: DocumentSymbolParams): List<DocumentSymbol> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = DocumentSymbol.serializer(),
            providers = configuration.entriesFor<LSDocumentSymbolProvider>(params.textDocument),
            getResults = { documentSymbolProvider ->
                tracer.traceProviderResults(
                    spanName = "provider.documentSymbol",
                    provider = documentSymbolProvider,
                    results = documentSymbolProvider.getDocumentSymbols(params),
                )
            },
        )
    }
}
