// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.intellij.openapi.diagnostic.fileLogger
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.intellijUriToLspUri
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit

private val LOG = fileLogger()

/**
 * Stops a command because a document changed after the command was computed: tells the user, then fails the
 * `workspace/executeCommand` request with [ErrorCodes.ContentModified].
 */
context(_: LspHandlerContext)
suspend fun refuseStaleDocument(): Nothing = lspClient.refuseStaleDocument()

/** Same as the context version of [refuseStaleDocument], for a caller that holds the client itself. */
suspend fun LspClient.refuseStaleDocument(): Nothing {
    notify(ShowMessageNotificationType, ShowMessageParams(MessageType.Warning, LspServerBundle.message("error.document.changed")))
    throwLspError(ExecuteCommand, LspServerBundle.message("error.action.not.available"), Unit, ErrorCodes.ContentModified, null)
}

/**
 * Sends [edit] with `workspace/applyEdit`. When the client does not apply it, which is what a client does with an
 * edit of an older document version, the command is refused with [refuseStaleDocument].
 */
suspend fun LspClient.applyEditOrRefuse(label: String?, edit: WorkspaceEdit) {
    val result = request(ApplyEditRequests.ApplyEdit, ApplyWorkspaceEditParams(label, edit))
    if (!result.applied) {
        LOG.info("The client did not apply the edit '$label': ${result.failureReason}, failed change: ${result.failedChanges}")
        refuseStaleDocument()
    }
}

/**
 * An edit of [fileUrl] (an IntelliJ file url) that names the document version the client sent last. The version
 * is `null` for a document the client does not keep open.
 */
context(_: LSServer)
fun versionedEdit(fileUrl: String, edits: List<TextEdit>): TextDocumentEdit = versionedEdit(DocumentUri(fileUrl.intellijUriToLspUri()), edits)

/** Same as the [versionedEdit] of a file url, for a document the client named by its [uri]. */
context(server: LSServer)
fun versionedEdit(uri: DocumentUri, edits: List<TextEdit>): TextDocumentEdit =
    TextDocumentEdit(TextDocumentIdentifier(uri, server.documents.getVersion(uri.uri)), edits)
