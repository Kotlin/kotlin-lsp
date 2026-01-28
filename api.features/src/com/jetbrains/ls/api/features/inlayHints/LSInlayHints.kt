// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inlayHints

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.InlayHint
import com.jetbrains.lsp.protocol.InlayHintParams

object LSInlayHints {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun inlayHints(params: InlayHintParams): List<InlayHint> {
        return LSConcurrentResponseHandler.respondDirectlyWithResultsCollectedConcurrently(
            providers = configuration.entriesFor<LSInlayHintsProvider>(params.textDocument),
            getResults = { inlayHintsProvider -> inlayHintsProvider.getInlayHints(params) },
        )
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun resolveInlayHint(hint: InlayHint): InlayHint {
        val uniqueId = getConfigurationEntryId(hint.data) ?: return hint
        val entry = configuration.entryById<LSInlayHintsProvider>(uniqueId) ?: return hint
        return entry.resolveInlayHint(hint) ?: hint
    }
}
