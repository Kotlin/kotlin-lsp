// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.helpers.useWithScope
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.codeActions.LSCodeActions
import com.jetbrains.ls.api.features.commands.LSCommand
import com.jetbrains.ls.api.features.completion.LSCompletion
import com.jetbrains.ls.api.features.definition.LSDefinition
import com.jetbrains.ls.api.features.diagnostics.LSDiagnostic
import com.jetbrains.ls.api.features.formatting.LSDocumentFormatting
import com.jetbrains.ls.api.features.hover.LSHover
import com.jetbrains.ls.api.features.implementation.LSImplementation
import com.jetbrains.ls.api.features.inlayHints.LSInlayHints
import com.jetbrains.ls.api.features.references.LSReferences
import com.jetbrains.ls.api.features.rename.LSRename
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokens
import com.jetbrains.ls.api.features.signatureHelp.LSSignatureHelp
import com.jetbrains.ls.api.features.symbols.LSDocumentSymbols
import com.jetbrains.ls.api.features.symbols.LSWorkspaceSymbols
import com.jetbrains.ls.api.features.typeDefinition.LSTypeDefinition
import com.jetbrains.ls.api.features.typeHierarchy.LSTypeHierarchy
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.CodeActions.CodeActionRequest
import com.jetbrains.lsp.protocol.CommandOrCodeAction
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.CompletionResolveRequestType
import com.jetbrains.lsp.protocol.CompletionResult
import com.jetbrains.lsp.protocol.DefinitionRequestType
import com.jetbrains.lsp.protocol.Diagnostics.DocumentDiagnosticRequestType
import com.jetbrains.lsp.protocol.DocumentSymbolRequest
import com.jetbrains.lsp.protocol.FormattingRequestType
import com.jetbrains.lsp.protocol.HoverRequestType
import com.jetbrains.lsp.protocol.Implementation.ImplementationRequest
import com.jetbrains.lsp.protocol.InlayHints.InlayHintRequestType
import com.jetbrains.lsp.protocol.InlayHints.ResolveInlayHint
import com.jetbrains.lsp.protocol.RangeFormattingRequestType
import com.jetbrains.lsp.protocol.ReferenceRequestType
import com.jetbrains.lsp.protocol.RenameRequestType
import com.jetbrains.lsp.protocol.RequestType
import com.jetbrains.lsp.protocol.SemanticTokensRequests.SemanticTokensFullRequest
import com.jetbrains.lsp.protocol.SemanticTokensRequests.SemanticTokensRangeRequest
import com.jetbrains.lsp.protocol.SignatureHelpRequest
import com.jetbrains.lsp.protocol.TypeDefinitionRequestType
import com.jetbrains.lsp.protocol.TypeHierarchyRequests.PrepareTypeHierarchyRequestType
import com.jetbrains.lsp.protocol.TypeHierarchyRequests.SubtypesRequestType
import com.jetbrains.lsp.protocol.TypeHierarchyRequests.SupertypesRequestType
import com.jetbrains.lsp.protocol.Workspace.WillRenameFiles
import com.jetbrains.lsp.protocol.WorkspaceSymbolRequests.WorkspaceSymbolRequest
import io.opentelemetry.api.trace.SpanKind
import kotlinx.coroutines.CoroutineScope

private val requestTracer = TelemetryManager.getTracer(Scope("lsp.requests"))

context(server: LSServer, configuration: LSConfiguration)
internal fun LspHandlersBuilder.features() {
    requestTraced(CodeActionRequest) { codeActionParams ->
        LSCodeActions.getCodeActions(codeActionParams).map { action -> CommandOrCodeAction.CodeAction(action) }
    }
    request(ExecuteCommand) { LSCommand.executeCommand(it) }
    requestTraced(CompletionRequestType) { completionParams ->
        LSCompletion.getCompletion(completionParams).let { items -> CompletionResult.MaybeIncomplete(items) }
    }
    request(CompletionResolveRequestType) { LSCompletion.resolveCompletion(it) }
    requestTraced(DefinitionRequestType) { LSDefinition.getDefinition(it) }
    requestTraced(ImplementationRequest) { LSImplementation.getImplementation(it) }
    requestTraced(TypeDefinitionRequestType) { LSTypeDefinition.getTypeDefinition(it) }
    requestTraced(DocumentDiagnosticRequestType) { LSDiagnostic.getDiagnostics(it) }
    request(HoverRequestType) { LSHover.getHover(it) }
    request(ReferenceRequestType) { LSReferences.getReferences(it) }
    request(SemanticTokensFullRequest) { LSSemanticTokens.semanticTokensFull(it) }
    request(SemanticTokensRangeRequest) { LSSemanticTokens.semanticTokensRange(it) }
    requestTraced(WorkspaceSymbolRequest) { LSWorkspaceSymbols.getSymbols(it) }
    requestTraced(DocumentSymbolRequest) { LSDocumentSymbols.getSymbols(it) }
    request(SignatureHelpRequest) { LSSignatureHelp.getSignatureHelp(it) }
    request(RenameRequestType) { LSRename.rename(it) }
    request(WillRenameFiles) { LSRename.renameFile(it) }
    request(FormattingRequestType) { LSDocumentFormatting.formatting(it) }
    request(RangeFormattingRequestType) { LSDocumentFormatting.rangeFormatting(it) }
    request(InlayHintRequestType) { LSInlayHints.inlayHints(it) }
    request(ResolveInlayHint) { LSInlayHints.resolveInlayHint(it) }
    request(PrepareTypeHierarchyRequestType) { LSTypeHierarchy.prepareTypeHierarchy(it) }
    request(SupertypesRequestType) { LSTypeHierarchy.supertypes(it) }
    request(SubtypesRequestType) { LSTypeHierarchy.subtypes(it) }
}

private fun <Req, Res, Err> LspHandlersBuilder.requestTraced(
    requestType: RequestType<Req, Res, Err>,
    handler: suspend context(LspHandlerContext) CoroutineScope.(Req) -> Res,
) {
    request(requestType) { request ->
        requestTracer.spanBuilder(requestType.method)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("lsp.method", requestType.method)
            .useWithScope {
                handler(request)
            }
    }
}
