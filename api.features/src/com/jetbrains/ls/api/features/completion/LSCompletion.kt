// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.entryById
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionParams

object LSCompletion {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getCompletion(params: CompletionParams): CompletionList {
        val results = entriesFor<LSCompletionProvider>(params.textDocument).map { it.provideCompletion(params) }
        return results.combined()
    }

    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
        val uniqueId = getConfigurationEntryId(item.data) ?: return item
        val entry = entryById<LSCompletionProvider>(uniqueId) ?: return item
        return entry.resolveCompletion(item) ?: item
    }

    private fun List<CompletionList>.combined(): CompletionList {
        if (isEmpty()) return CompletionList.EMPTY
        if (size == 1) return first()

        require(none { it.isIncomplete }) { "Combining incomplete results is not yet supported" }
        require(all { it.itemDefaults == null }) { "Combining itemDefaults is not yet supported" }
        return CompletionList(
            isIncomplete = false,
            items = flatMap { it.items }
        )
    }
}
