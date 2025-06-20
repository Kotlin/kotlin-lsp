// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolParams

object LSDocumentSymbols {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getSymbols(params: DocumentSymbolParams): List<DocumentSymbol> =
        LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            params.partialResultToken,
            DocumentSymbol.serializer(),
            entriesFor<LSDocumentSymbolProvider>(params.textDocument),
        ) {
            it.getDocumentSymbols(params)
        }
}