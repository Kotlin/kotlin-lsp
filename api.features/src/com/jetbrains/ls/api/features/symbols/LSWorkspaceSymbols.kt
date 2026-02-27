// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProviderResults
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams

object LSWorkspaceSymbols {
    val scope: Scope = Scope("lsp.workspaceSymbols")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getSymbols(params: WorkspaceSymbolParams): List<WorkspaceSymbol> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = WorkspaceSymbol.serializer(),
            providers = configuration.entries<LSWorkspaceSymbolProvider>(),
            getResults = { workspaceSymbolProvider ->
                tracer.traceProviderResults(
                    spanName = "provider.workspaceSymbol",
                    provider = workspaceSymbolProvider,
                    results = workspaceSymbolProvider.getWorkspaceSymbols(params),
                )
            },
        )
    }
}
