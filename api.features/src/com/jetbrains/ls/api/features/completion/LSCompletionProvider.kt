// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionParams

interface LSCompletionProvider : LSLanguageSpecificConfigurationEntry, LSUniqueConfigurationEntry {
    val supportsResolveRequest: Boolean

    context(LSServer)
    suspend fun provideCompletion(params: CompletionParams): CompletionList

    context(LSServer)
    suspend fun resolveCompletion(completionItem: CompletionItem): CompletionItem? = null
}
