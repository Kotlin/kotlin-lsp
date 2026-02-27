// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.ls.api.features.utils.traceProviderResults
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionParams

object LSCompletion {
    val scope: Scope = Scope("lsp.completion")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getCompletion(params: CompletionParams): CompletionList {
        val providers = configuration.entriesFor<LSCompletionProvider>(params.textDocument)
        val results = providers.map { completionProvider ->
            tracer.traceProviderResults(
                spanName = "provider.completion",
                provider = completionProvider,
                getResult = { completionProvider.provideCompletion(params) },
            )
        }
        return results.combined()
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
        val uniqueId = getConfigurationEntryId(item.data) ?: return item
        val completionProvider = configuration.entryById<LSCompletionProvider>(uniqueId) ?: return item
        return tracer.traceProviderResults(
            spanName = "provider.completion.resolve",
            provider = completionProvider,
            getResult = { completionProvider.resolveCompletion(item) ?: item },
        )
    }

    private fun List<CompletionList>.combined(): CompletionList {
        if (isEmpty()) return CompletionList.EMPTY
        if (size == 1) return first()

        require(none { it.isIncomplete }) { "Combining incomplete results is not yet supported" }
        require(all { it.itemDefaults == null }) { "Combining itemDefaults is not yet supported" }
        return CompletionList(
            isIncomplete = false,
            items = flatMap { it.items },
        )
    }
}
