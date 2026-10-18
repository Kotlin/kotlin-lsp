// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.DEFAULT_WORKSPACE_SYMBOL_LIMIT
import com.jetbrains.ls.api.core.features.symbolLimit
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.flow.onEach
import java.util.Collections
import java.util.IdentityHashMap

object LSWorkspaceSymbols {
    val scope: Scope = Scope("lsp.workspaceSymbols")
    private val tracer = TelemetryManager.getTracer(scope)

    /**
     * At most [WorkspaceSymbolParams.limit] symbols ([DEFAULT_WORKSPACE_SYMBOL_LIMIT] by default), the best matching names over all
     * providers, each name ranked by [LSWorkspaceSymbolProvider.workspaceSymbolRank] of its provider.
     * Each contributor stops at the limit, so the answer comes when all of them are done.
     */
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getSymbols(params: WorkspaceSymbolParams): List<WorkspaceSymbol> {
        val limit = params.symbolLimit()
        // The rank of each symbol by its own provider; the flows of the providers fill it concurrently.
        val ranks = Collections.synchronizedMap(IdentityHashMap<WorkspaceSymbol, Int>())
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = WorkspaceSymbol.serializer(),
            providers = configuration.entries<LSWorkspaceSymbolProvider>(),
            arrange = { symbols -> symbols.sortedByDescending { ranks.getValue(it) }.take(limit) },
            getResults = { workspaceSymbolProvider ->
                val rank = workspaceSymbolProvider.workspaceSymbolRank(params.query)
                tracer.traceProvider(
                    spanName = "provider.workspaceSymbol",
                    provider = workspaceSymbolProvider,
                    resultsFlow = workspaceSymbolProvider.getWorkspaceSymbols(params),
                ).onEach { ranks[it] = rank(it.name) }
            },
        )
    }

    /** `workspaceSymbol/resolve`: the first provider that knows [symbol] fills its location; none gives [symbol] back unchanged. */
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun resolve(symbol: WorkspaceSymbol): WorkspaceSymbol {
        if (symbol.location is WorkspaceSymbol.SymbolLocation.Full) return symbol
        for (provider in configuration.entries<LSWorkspaceSymbolProvider>()) {
            val resolved = tracer.traceProvider(spanName = "provider.workspaceSymbolResolve", provider = provider) {
                provider.resolveWorkspaceSymbol(symbol)
            }
            if (resolved != null) return resolved
        }
        return symbol
    }
}
