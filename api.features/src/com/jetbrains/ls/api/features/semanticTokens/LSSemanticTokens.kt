// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entries
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.semanticTokens.encoding.SemanticTokensEncoder
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensParams
import com.jetbrains.lsp.protocol.SemanticTokensRangeParams

// todo send partial results here
object LSSemanticTokens {
    context(_: LSServer, _: LSConfiguration)
    suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        val providers = entriesFor<LSSemanticTokensProvider>(params.textDocument)
        val result = providers.flatMap { it.full(params) }
        val registry = createRegistry()
        val encoded = SemanticTokensEncoder.encode(result, registry)
        return SemanticTokens(resultId = null, data = encoded)
    }

    context(_: LSServer, _: LSConfiguration)
    suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens {
        val providers = entriesFor<LSSemanticTokensProvider>(params.textDocument)
        val result = providers.flatMap { it.range(params) }
        val registry = createRegistry()
        val encoded = SemanticTokensEncoder.encode(result, registry)
        return SemanticTokens(resultId = null, data = encoded)
    }

    context(_: LSConfiguration)
    fun createRegistry(): LSSemanticTokenRegistry {
        val registries = entries<LSSemanticTokensProvider>().map { it.createRegistry() }
        if (registries.isEmpty()) return LSSemanticTokenRegistry.EMPTY
        if (registries.size == 1) return registries.first()
        return LSSemanticTokenRegistry(
            registries.flatMap { it.types }.distinct(),
            registries.flatMap { it.modifiers }.distinct(),
        )
    }
}