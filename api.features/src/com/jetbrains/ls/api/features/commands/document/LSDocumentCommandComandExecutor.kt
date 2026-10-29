// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands.document

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.commands.LSCommandExecutor
import com.jetbrains.ls.api.features.commands.applyEditOrRefuse
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

fun interface LSDocumentCommandExecutor : LSCommandExecutor {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun executeForDocument(documentUri: DocumentUri, otherArgs: List<JsonElement>): List<TextEdit>

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun execute(arguments: List<JsonElement>): JsonElement {
        require(arguments.isNotEmpty()) { "Expected >= 1 argument, got: ${arguments.size}" }
        val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments.first())
        // Read before the edits: the analysis context that computes them opens later, so its text is this version
        // or a newer one. A newer text gets an older version, and the client rejects the edit; the other way round,
        // a new version with offsets of an old text, cannot happen.
        val version = server.documents.getVersion(documentUri.uri)

        val edits = executeForDocument(documentUri, arguments.drop(1))
        if (edits.isNotEmpty()) {
            val edit = TextDocumentEdit(TextDocumentIdentifier(documentUri, version), edits)
            lspClient.applyEditOrRefuse(label = null, edit = WorkspaceEdit(documentChanges = listOf(edit)))
        }
        // TODO we just need to return smth, not sure that the result is used
        return JsonPrimitive(true)
    }
}