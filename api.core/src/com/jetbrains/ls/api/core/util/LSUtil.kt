package com.jetbrains.ls.api.core.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextDocumentPositionParams
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import kotlinx.coroutines.CoroutineScope

context(LSServer)
suspend fun <R> withAnalysisContext(
    params: TextDocumentPositionParams,
    action: suspend context(LSAnalysisContext, CoroutineScope) () -> R,
): R = withAnalysisContext(params.textDocument, action)

context(LSServer)
suspend fun <R> withAnalysisContext(
    document: TextDocumentIdentifier,
    action: suspend context(LSAnalysisContext, CoroutineScope) () -> R,
): R = withAnalysisContext(document.uri, action)

context(LSServer)
suspend fun <R> withAnalysisContext(
    uri: DocumentUri,
    action: suspend context(LSAnalysisContext, CoroutineScope) () -> R,
): R = withAnalysisContext(uri.uri, action)


val VirtualFile.uri: URI
    get() = url.intellijUriToLspUri()

fun Document.offsetByPosition(position: Position): Int = 
    getLineStartOffset(position.line) + position.character

fun Document.positionByOffset(offset: Int): Position {
    val line = getLineNumber(offset) 
    return Position(line = line, character = offset - getLineStartOffset(line))
}

fun URI.findVirtualFile(): VirtualFile? =
    VirtualFileManager.getInstance().findFileByUrl(lspUriToIntellijUri())

fun TextRange.toLspRange(document: Document): Range =
    Range(
        document.positionByOffset(startOffset),
        document.positionByOffset(endOffset),
    )

fun Range.toTextRange(document: Document): TextRange =
    TextRange(
        document.offsetByPosition(start),
        document.offsetByPosition(end),
    )

fun TextDocumentIdentifier.findVirtualFile(): VirtualFile? = uri.uri.findVirtualFile()

fun DocumentUri.findVirtualFile(): VirtualFile? = uri.findVirtualFile()

fun TextDocumentPositionParams.findVirtualFile(): VirtualFile? = textDocument.findVirtualFile()

fun VirtualFile.isFromLibrary(): Boolean {
    val scheme = uri.scheme
    return scheme == "jrt" || scheme == "jar" || uri.uri.contains("!")
}