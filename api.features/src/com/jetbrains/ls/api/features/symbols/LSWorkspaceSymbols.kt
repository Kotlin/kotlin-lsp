// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entries
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams

object LSWorkspaceSymbols {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getSymbols(params: WorkspaceSymbolParams): List<WorkspaceSymbol> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = WorkspaceSymbol.serializer(),
            providers = entries<LSWorkspaceSymbolProvider>(),
            getResults = { workspaceSymbolProvider -> workspaceSymbolProvider.getWorkspaceSymbols(params) },
        )
    }
}
