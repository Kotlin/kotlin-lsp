// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands.document

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.commands.LSCommandExecutor
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
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

        val edits = executeForDocument(documentUri, arguments.drop(1))
        if (edits.isNotEmpty()) {
            // TODO LSP-235 handle errors during application
            lspClient.request(
                ApplyEditRequests.ApplyEdit,
                ApplyWorkspaceEditParams(
                    label = null,
                    edit = WorkspaceEdit(
                        changes = mapOf(documentUri to edits)
                    ),
                ),
            )
        }
        // TODO we just need to return smth, not sure that the result is used
        return JsonPrimitive(true)
    }
}