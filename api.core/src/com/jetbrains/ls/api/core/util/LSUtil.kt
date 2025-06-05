// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.jetbrains.lsp.protocol.*

val VirtualFile.uri: URI
    get() = url.intellijUriToLspUri()

fun Document.offsetByPosition(position: Position): Int = 
    getLineStartOffset(position.line) + position.character

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