// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inlayHints

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.ls.api.features.entryById
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.InlayHint
import com.jetbrains.lsp.protocol.InlayHintParams

object LSInlayHints {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun inlayHints(params: InlayHintParams): List<InlayHint> {
        return LSConcurrentResponseHandler.respondDirectlyWithResultsCollectedConcurrently(
            entriesFor<LSInlayHintsProvider>(params.textDocument),
        ) {
            it.getInlayHints(params)
        }
    }
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun resolveInlayHint(hint: InlayHint): InlayHint {
        val uniqueId = getConfigurationEntryId(hint.data) ?: return hint
        val entry = entryById<LSInlayHintsProvider>(uniqueId) ?: return hint
        return entry.resolveInlayHint(hint) ?: hint
    }
}