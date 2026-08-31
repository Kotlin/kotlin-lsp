// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.documentHighlight

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentHighlight
import com.jetbrains.lsp.protocol.DocumentHighlightParams

object LSDocumentHighlight {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getDocumentHighlights(params: DocumentHighlightParams): List<DocumentHighlight>? {
        return configuration.entriesFor<LSDocumentHighlightProvider>(params.textDocument).firstNotNullOfOrNull { it.getDocumentHighlights(params) }
    }
}
