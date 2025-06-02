// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

object LSCompletion {
    context(LSServer, LSConfiguration)
    suspend fun getCompletion(params: CompletionParams): CompletionList {
        // todo support partial results
        val results = entriesFor<LSCompletionProvider>(params.textDocument).map { it.provideCompletion(params) }
        return results.combined()
    }

    context(LSServer, LSConfiguration)
    suspend fun resolveCompletion(params: CompletionItem): CompletionItem {
        val data: CompletionItemData = runCatching {
            val data = params.data ?: return params
            LSP.json.decodeFromJsonElement<CompletionItemData>(data)
        }.getOrNull() ?: return params
        return entries<LSCompletionProvider>().firstOrNull { it.uniqueId == data.providerId }?.resolveCompletion(params) ?: params
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
    val providerId: String,
    val params: CompletionParams,
    val lookupString: String,
    val pointer: PsiSerializablePointer?
)
