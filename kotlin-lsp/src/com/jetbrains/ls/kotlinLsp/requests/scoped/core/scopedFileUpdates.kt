// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.scoped.core

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.documents
import com.jetbrains.ls.kotlinLsp.requests.scoped.ensureRequestUriIsScoped
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.DocumentSync

context(_: LSServer)
internal fun LspHandlersBuilder.scopedFileUpdateRequests() {
    notification(DocumentSync.DidOpen) { didOpen ->
        ensureRequestUriIsScoped(didOpen.textDocument.uri)
        documents.didOpen(
            uri = didOpen.textDocument.uri.uri,
            fileText = didOpen.textDocument.text,
            version = didOpen.textDocument.version,
            languageId = didOpen.textDocument.languageId
        )
    }

    notification(DocumentSync.DidClose) { didClose ->
        documents.didClose(didClose.textDocument.uri.uri)
    }

    notification(DocumentSync.DidChange) { didChange ->
        documents.didChange(
            uri = didChange.textDocument.uri.uri,
            changes = didChange.contentChanges,
        )
    }
}