// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.codeActions

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.onEach

object LSCodeActions {
    context(LSServer, LSConfiguration, LspHandlerContext)
    suspend fun getCodeActions(params: CodeActionParams): List<CodeAction> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            params.partialResultToken,
            CodeAction.serializer(),
            getProviders(params),
        ) { provider ->
            provider.getCodeActions(params)
                .onEach { it.ensureCanBeProvidedBy(provider) }
        }
    }

    context(LSConfiguration)
    fun supportedCodeActionKinds(): List<CodeActionKind> {
        return entries<LSCodeActionProvider>().flatMapTo(mutableSetOf()) { it.providesOnlyKinds }.toList()
    }


    private fun CodeAction.ensureCanBeProvidedBy(provider: LSCodeActionProvider) {
        check(kind in provider.providesOnlyKinds) {
            "Code action $title is not supported by ${provider.javaClass.name}. The only supported kinds are: ${provider.providesOnlyKinds.joinToString()}"
        }
    }

    context(LSConfiguration)
    private fun getProviders(params: CodeActionParams): List<LSCodeActionProvider> {
        val all = entriesFor<LSCodeActionProvider>(params.textDocument)
        val only = params.context.only?.toSet() ?: return all
        return all.filter { provider ->
            provider.providesOnlyKinds.any { it in only }
        }
    }
}