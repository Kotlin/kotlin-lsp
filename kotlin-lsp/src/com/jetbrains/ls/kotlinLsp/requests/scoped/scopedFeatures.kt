// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.scoped

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.documents
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.completion.LSCompletion
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.*

context(_: LSServer, _: LSConfiguration)
internal fun LspHandlersBuilder.scopedFeatures() {
    request(CompletionRequestType) { params ->
        ensureRequestUriIsScoped(params.textDocument.uri)
        require(documents.getVersion(params.textDocument.uri.uri) != null) {
            "Document ${params.textDocument.uri.uri} should be opened before requesting completion"
        }
        LSCompletion.getCompletion(params).let {
            CompletionResult.MaybeIncomplete(it)
        }
    }

    request(CompletionResolveRequestType) { params ->
        LSCompletion.resolveCompletion(params)
    }
}