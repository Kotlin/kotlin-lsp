// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.SemanticTokensParams
import com.jetbrains.lsp.protocol.SemanticTokensRangeParams

interface LSSemanticTokensProvider : LSLanguageSpecificConfigurationEntry {
    fun createRegistry(): LSSemanticTokenRegistry

    /**
     * LSP method `textDocument/semanticTokens/full`
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun full(params: SemanticTokensParams): List<LSSemanticTokenWithRange>

    /**
     * LSP method `textDocument/semanticTokens/range`
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun range(params: SemanticTokensRangeParams): List<LSSemanticTokenWithRange>
}
