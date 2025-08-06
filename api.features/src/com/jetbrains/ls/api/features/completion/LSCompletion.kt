// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.entryById
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object LSCompletion {
    context(_: LSServer, _: LSConfiguration)
    suspend fun getCompletion(params: CompletionParams): CompletionList {
        // todo support partial results
        val results = entriesFor<LSCompletionProvider>(params.textDocument).map { it.provideCompletion(params) }
        return results.combined()
    }

    context(_: LSServer, _: LSConfiguration)
    suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
        val uniqueId = getConfigurationEntryId(item.data) ?: return item
        val entry = entryById<LSCompletionProvider>(uniqueId) ?: return item
        return entry.resolveCompletion(item) ?: item
    }

    private fun List<CompletionList>.combined(): CompletionList {
        if (isEmpty()) return CompletionList.EMPTY_COMPLETE
        if (size == 1) return first()

        require(none { it.isIncomplete }) { "Combining incomplete results is not yet supported" }
        require(all { it.itemDefaults == null }) { "Combining itemDefaults is not yet supported" }
        return CompletionList(
            isIncomplete = false,
            items = flatMap { it.items }
        )
    }
}

//todo should be later moved to a common features module
@Serializable
data class CompletionItemData(
    val params: CompletionParams,
    val additionalData: JsonElement,
    override val configurationEntryId: LSUniqueConfigurationEntry.UniqueId,
) : ResolveDataWithConfigurationEntryId
