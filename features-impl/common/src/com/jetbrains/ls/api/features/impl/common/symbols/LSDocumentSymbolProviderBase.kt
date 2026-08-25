// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizer
import com.jetbrains.ls.api.core.features.lsDocumentSymbols
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.symbols.LSDocumentSymbolProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

abstract class LSDocumentSymbolProviderBase<T> : LSDocumentSymbolProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDocumentSymbols(params: DocumentSymbolParams): Flow<DocumentSymbol> = flow {
        server.withAnalysisContext {
            lsDocumentSymbols(project, params, createCustomizer())
        }.forEach { documentSymbol -> emit(documentSymbol) }
    }

    abstract fun createCustomizer(): LSDocumentSymbolCustomizer<T>
}