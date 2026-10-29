// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.ls.api.core.LSDocuments
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.VersionedText
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
 * Sends [edit] with `workspace/applyEdit`, and fails the command when the client does not apply it.
 *
 * When a document of the edit changed since the edit was built ([staleDocuments]), the command is refused with
 * [refuseStaleDocument]. The check sees the new version: the server handles `textDocument/didChange` inline, so it
 * comes before the answer. Any other rejection is the client's own (the user declined the change, the file is out of
 * the workspace), and the client tells the user itself, so the request fails with [ErrorCodes.RequestFailed] and
 * the client's reason, without a message.
 */
context(server: LSServer)
suspend fun LspClient.applyEditOrRefuse(label: String?, edit: WorkspaceEdit) {
    val result = request(ApplyEditRequests.ApplyEdit, ApplyWorkspaceEditParams(label, edit))
    if (result.applied) return
    LOG.info("The client did not apply the edit '$label': ${result.failureReason}, failed change: ${result.failedChanges}")
    if (staleDocuments(edit) { server.documents.getVersion(it.uri) }.isNotEmpty()) {
        refuseStaleDocument()
    }
    val reason = result.failureReason ?: LspServerBundle.message("error.edit.not.applied")
    throwLspError(ExecuteCommand, reason, Unit, ErrorCodes.RequestFailed, null)
}

/**
 * The documents of [edit] that changed since it was built: each names a version, and [currentVersion] now gives
 * another one. An edit without a version (a closed file) and a file operation are never stale.
 */
fun staleDocuments(edit: WorkspaceEdit, currentVersion: (DocumentUri) -> Int?): List<DocumentUri> =
    edit.documentChanges.orEmpty()
        .filterIsInstance<TextDocumentEdit>()
        .filter { it.textDocument.version != null && it.textDocument.version != currentVersion(it.textDocument.uri) }
        .map { it.textDocument.uri }

/**
 * An edit of [fileUrl] (an IntelliJ file url). See the [versionedEdit] of a [DocumentUri].
 */
context(_: LSServer)
suspend fun LspClient.versionedEdit(
    fileUrl: String,
    sourceText: CharSequence,
    edits: List<TextEdit>,
    sentVersion: Int? = null,
): TextDocumentEdit =
    versionedEdit(DocumentUri(fileUrl.intellijUriToLspUri()), sourceText, edits, sentVersion)

/**
 * An edit of [uri] whose [edits] refer to [sourceText]. It names the client version whose text is [sourceText],
 * so the client applies it only to that text. The version is `null` for a document the client does not keep open.
 *
 * The version and the text the client sent last are read together ([LSDocuments.getVersionedText]), and the
 * version is taken only when that text is [sourceText]. A `didChange` the server handled after [sourceText] was
 * read would otherwise lend its new version to offsets of the old text, and the client would apply them to the
 * wrong place. A different text refuses the command with [refuseStaleDocument].
 *
 * [sentVersion] is the version that an earlier step of the same command named for this document. When the client
 * still reports it, the client has not sent the change of that step yet (a test client never does), so the edit
 * names it again: a client that did apply that step rejects it, which is a safe stop.
 */
context(server: LSServer)
suspend fun LspClient.versionedEdit(
    uri: DocumentUri,
    sourceText: CharSequence,
    edits: List<TextEdit>,
    sentVersion: Int? = null,
): TextDocumentEdit {
    val document = server.documents.getVersionedText(uri.uri)
                   ?: return TextDocumentEdit(TextDocumentIdentifier(uri, null), edits)
    if (!document.fitsEditOf(sourceText, sentVersion)) {
        LOG.info("The document $uri changed since the edit was built: the client is at version ${document.version}")
        refuseStaleDocument()
    }
    return TextDocumentEdit(TextDocumentIdentifier(uri, document.version), edits)
}

/**
 * Whether an edit whose offsets refer to [sourceText] may name the version of [this], the text the client sent
 * last: that text is [sourceText], or the version is still [sentVersion], the one an earlier step named. See
 * [versionedEdit]. [sourceText] has `\n` line separators, as the text of an IntelliJ document has.
 */
fun VersionedText.fitsEditOf(sourceText: CharSequence, sentVersion: Int?): Boolean =
    version == sentVersion || StringUtil.equals(StringUtil.convertLineSeparators(text.toString()), sourceText)
