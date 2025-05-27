// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.codeActions.LSCodeActions
import com.jetbrains.ls.api.features.commands.LSCommand
import com.jetbrains.ls.api.features.completion.LSCompletion
import com.jetbrains.ls.api.features.definition.LSDefinition
import com.jetbrains.ls.api.features.diagnostics.LSDiagnostic
import com.jetbrains.ls.api.features.symbols.LSDocumentSymbols
import com.jetbrains.ls.api.features.hover.LSHover
import com.jetbrains.ls.api.features.references.LSReferences
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokens
import com.jetbrains.ls.api.features.symbols.LSWorkspaceSymbols
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.*
import com.jetbrains.lsp.protocol.CodeActions.CodeActionRequest
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.Diagnostics.DocumentDiagnosticRequestType
import com.jetbrains.lsp.protocol.SemanticTokensRequests.SemanticTokensFullRequest
import com.jetbrains.lsp.protocol.WorkspaceSymbolRequests.WorkspaceSymbolRequest

context(LSServer, LSConfiguration)
internal fun LspHandlersBuilder.features() {
    request(CodeActionRequest) { LSCodeActions.getCodeActions(it) }
    request(ExecuteCommand) { LSCommand.executeCommand(it) }
    request(CompletionRequestType) { LSCompletion.getCompletion(it) }
    request(CompletionResolveRequestType) { LSCompletion.resolveCompletion(it) }
    request(DefinitionRequestType) { LSDefinition.getDefinition(it) }
    request(DocumentDiagnosticRequestType) { LSDiagnostic.getDiagnostics(it) }
    request(HoverRequestType) { LSHover.getHover(it) }
    request(ReferenceRequestType) { LSReferences.getReferences(it) }
    request(SemanticTokensFullRequest) { LSSemanticTokens.semanticTokensFull(it) }
    request(WorkspaceSymbolRequest) { LSWorkspaceSymbols.getSymbols(it) }
    request(DocumentSymbolRequest) { LSDocumentSymbols.getSymbols(it) }
}