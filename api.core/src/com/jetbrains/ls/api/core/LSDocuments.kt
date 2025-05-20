package com.jetbrains.ls.api.core

import com.jetbrains.lsp.protocol.TextDocumentContentChangeEvent
import com.jetbrains.lsp.protocol.URI

interface LSDocuments {
    
    suspend fun didOpen(uri: URI, fileText: String, version: Long, languageId: String)
    
    suspend fun didClose(uri: URI)
    
    suspend fun didChange(uri: URI, changes: List<TextDocumentContentChangeEvent>)
}
