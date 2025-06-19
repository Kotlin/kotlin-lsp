// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.jetbrains.lsp.protocol.*

val VirtualFile.uri: URI
    get() = url.intellijUriToLspUri()

/**
 * Calculates the absolute offset in the document text based on the given position (line and character offset).
 *
 * @param position The position in the document, represented by a line number (zero-based) and
 *                 character offset in that line (zero-based).
 * @return The absolute offset in the document, which represents the character index corresponding
 *         to the given position. If the position line is outside the document bounds, returns the document
 *         end offset. If the character offset is outside the line bounds, returns the line end offset.
 */
fun Document.offsetByPosition(position: Position): Int {
    val textLength = textLength
    if (position.line >= lineCount) {
        // lsp position may be outside the document, which means the end of the document
        return textLength
    }
    val lineStart = getLineStartOffset(position.line)
    val lineEnd = getLineEndOffset(position.line)
    if (position.character > lineEnd - lineStart) {
        // lsp position may be outside the line range, which means the end of the line
        return lineEnd
    }
    return lineStart + position.character
}

fun Document.positionByOffset(offset: Int): Position {
    val line = getLineNumber(offset) 
    return Position(line = line, character = offset - getLineStartOffset(line))
}

fun URI.findVirtualFile(): VirtualFile? =
    lspUriToIntellijUri()?.let { VirtualFileManager.getInstance().findFileByUrl(it) }

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