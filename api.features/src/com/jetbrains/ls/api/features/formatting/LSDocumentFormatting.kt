// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.formatting

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentFormattingParams
import com.jetbrains.lsp.protocol.DocumentRangeFormattingParams
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit

object LSDocumentFormatting {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun formatting(params: DocumentFormattingParams): List<TextEdit>? {
        val provider = provider(params.textDocument) ?: return null
        return provider.getFormatting(params)
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit>? {
        val provider = provider(params.textDocument) ?: return null
        return provider.getFormattingRanged(params)
    }

    context(configuration: LSConfiguration)
    private fun provider(textDocument: TextDocumentIdentifier): LSFormattingProvider? {
        val providers = configuration.entriesFor<LSFormattingProvider>(textDocument)
        return when (providers.size) {
            0 -> null
            1 -> providers.first()
            else -> {
                error("Multiple signature help ${LSFormattingProvider::class.qualifiedName} found for ${textDocument}: ${providers}")
            }
        }
    }
}
