// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests

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
import com.jetbrains.ls.api.features.typeHierarchy.LSTypeHierarchy
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.*
import com.jetbrains.lsp.protocol.CodeActions.CodeActionRequest
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.Diagnostics.DocumentDiagnosticRequestType
import com.jetbrains.lsp.protocol.InlayHints.InlayHintRequestType
import com.jetbrains.lsp.protocol.InlayHints.ResolveInlayHint
import com.jetbrains.lsp.protocol.SemanticTokensRequests.SemanticTokensFullRequest
import com.jetbrains.lsp.protocol.SemanticTokensRequests.SemanticTokensRangeRequest
import com.jetbrains.lsp.protocol.TypeHierarchyRequests.PrepareTypeHierarchyRequestType
import com.jetbrains.lsp.protocol.TypeHierarchyRequests.SubtypesRequestType
import com.jetbrains.lsp.protocol.TypeHierarchyRequests.SupertypesRequestType
import com.jetbrains.lsp.protocol.WorkspaceSymbolRequests.WorkspaceSymbolRequest

context(_: LSServer, _: LSConfiguration)
internal fun LspHandlersBuilder.features() {
    request(CodeActionRequest) { LSCodeActions.getCodeActions(it).map { CommandOrCodeAction.CodeAction(it) } }
    request(ExecuteCommand) { LSCommand.executeCommand(it) }
    request(CompletionRequestType) { LSCompletion.getCompletion(it).let { CompletionResult.MaybeIncomplete(it) } }
    request(CompletionResolveRequestType) { LSCompletion.resolveCompletion(it) }
    request(DefinitionRequestType) { LSDefinition.getDefinition(it) }
    request(Implementation.ImplementationRequest) { LSImplementation.getImplementation(it) }
    request(DocumentDiagnosticRequestType) { LSDiagnostic.getDiagnostics(it) }
    request(HoverRequestType) { LSHover.getHover(it) }
    request(ReferenceRequestType) { LSReferences.getReferences(it) }
    request(SemanticTokensFullRequest) { LSSemanticTokens.semanticTokensFull(it) }
    request(SemanticTokensRangeRequest) { LSSemanticTokens.semanticTokensRange(it) }
    request(WorkspaceSymbolRequest) { LSWorkspaceSymbols.getSymbols(it) }
    request(DocumentSymbolRequest) { LSDocumentSymbols.getSymbols(it) }
    request(SignatureHelpRequest) { LSSignatureHelp.getSignatureHelp(it) }
    request(RenameRequestType) { LSRename.rename(it) }
    request(Workspace.WillRenameFiles) { LSRename.renameFile(it) }
    request(FormattingRequestType) { LSDocumentFormatting.formatting(it) }
    request(RangeFormattingRequestType) { LSDocumentFormatting.rangeFormatting(it) }
    request(InlayHintRequestType) { LSInlayHints.inlayHints(it) }
    request(ResolveInlayHint) { LSInlayHints.resolveInlayHint(it) }
    request(PrepareTypeHierarchyRequestType) { LSTypeHierarchy.prepareTypeHierarchy(it) }
    request(SupertypesRequestType) { LSTypeHierarchy.supertypes(it) }
    request(SubtypesRequestType) { LSTypeHierarchy.subtypes(it) }
}
