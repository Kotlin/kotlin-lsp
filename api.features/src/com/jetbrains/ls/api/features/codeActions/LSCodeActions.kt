package com.jetbrains.ls.api.features.codeActions

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionParams

object LSCodeActions {
    context(LSServer, LSConfiguration, LspHandlerContext)
    suspend fun getCodeActions(params: CodeActionParams): List<CodeAction> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            params.partialResultToken,
            CodeAction.serializer(),
            entriesFor<LSCodeActionProvider>(params.textDocument),
        ) {
            it.getCodeActions(params)
        }
    }
}