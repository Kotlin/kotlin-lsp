// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core

import com.jetbrains.lsp.protocol.TextDocumentContentChangeEvent
import com.jetbrains.lsp.protocol.URI

interface LSDocuments {
    
    suspend fun didOpen(uri: URI, fileText: String, version: Int, languageId: String)
    
    suspend fun didClose(uri: URI)

    suspend fun didChange(uri: URI, changes: List<TextDocumentContentChangeEvent>)

    suspend fun invalidate(uriSeq: Sequence<URI>)

    fun getVersion(uri: URI): Int?
}
