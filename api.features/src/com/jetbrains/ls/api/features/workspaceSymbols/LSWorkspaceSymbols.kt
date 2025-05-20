package com.jetbrains.ls.api.features.workspaceSymbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams

object LSWorkspaceSymbols {
    context(LSServer, LSConfiguration, LspHandlerContext)
    suspend fun getSymbols(params: WorkspaceSymbolParams): List<WorkspaceSymbol> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            params.partialResultToken,
            WorkspaceSymbol.serializer(),
            entries<LSWorkspaceSymbolProvider>(),
        ) {
            it.getWorkspaceSymbols(params)
        }
    }
}