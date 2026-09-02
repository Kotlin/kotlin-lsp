// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.window

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.ShowDocument
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowDocumentResult

/**
 * Asks the client to show [params], and returns `null` when the client does not declare
 * `window.showDocument.support`.
 *
 * `window/showDocument` is optional in LSP, and a client without it answers with a `MethodNotFound` error. That
 * error fails the whole request that sent it, so a caret placement that the user does not even see would break
 * the completion or the fix that asked for it.
 */
context(server: LSServer)
suspend fun LspClient.showDocumentIfSupported(params: ShowDocumentParams): ShowDocumentResult? =
    when {
        server.config.clientSupportsShowDocument -> request(ShowDocument, params)
        else -> null
    }
