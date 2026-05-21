// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.IncorrectOperationException
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.fileExtension
import com.jetbrains.ls.api.core.util.fileName
import com.jetbrains.ls.api.core.util.scheme
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.ls.api.features.textEdits.fileChanges
import com.jetbrains.ls.snapshot.api.impl.core.asURI
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import com.jetbrains.lsp.implementation.LspException
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.FileChange
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.RenameRequestType
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.URI

/**
 * Calculates the difference in names between two URIs.
 * @see [NameChange]
 */
fun computeNameChange(old: URI, new: URI, isDirectory: Boolean): NameChange? {
    if (old.scheme != new.scheme) return null
    val oldFileUrl = old.toFileUrl() ?: return null
    val newFileUrl = new.toFileUrl() ?: return null
    if (oldFileUrl.parent != newFileUrl.parent) return null

    return if (isDirectory) computeDirectoryNameChange(old, new) else computeFileNameChange(old, new)
}

private fun computeDirectoryNameChange(old: URI, new: URI): NameChange? {
    val newExtension = new.fileExtension
    val oldExtension = old.fileExtension

    if (oldExtension != null || newExtension != null) return null

    val oldName = old.fileName
    val newName = new.fileName
    if (oldName == newName) return null

    return NameChange(
        oldName,
        newName
    )
}

private fun computeFileNameChange(old: URI, new: URI): NameChange? {
    val newExtension = new.fileExtension
    val oldExtension = old.fileExtension

    if (oldExtension == null || newExtension == null || newExtension != oldExtension) return null


    val oldName = old.fileName
    val newName = new.fileName
    if (oldName == newName) return null
    return NameChange(
        oldName.getPureName(oldExtension),
        newName.getPureName(newExtension)
    )
}

/**
 * Performs the rename operation based on the provided context.
 * @see RenameContext
 */
context(server: LSServer, _: LSAnalysisContext)
suspend fun doRename(context: RenameContext): List<FileChange>? {
    val renamer = readAction {
        val target = context.target
        if (!target.isValid) return@readAction null
        val primaryElement = RenamePsiElementProcessor.forElement(target).substituteElementToRename(target, null) ?: target
        if (primaryElement is PsiCompiledElement) {
            throwLspError(RenameRequestType, "This element cannot be renamed", Unit, ErrorCodes.InvalidParams)
        }
        Renamer(target.project, target, context.newName, false, false)
    } ?: return null

    try {
        writeIntentUserReadAction {
            renamer.rename()
        }
    } catch (ex: Throwable) {
        when (ex) {
            is LspException -> throw ex
            else -> {
                val cause = generateSequence(ex) { it.cause?.takeIf { c -> c != it } }
                    .filterIsInstance<IncorrectOperationException>()
                    .firstOrNull() ?: ex

                throwLspError(
                    RenameRequestType,
                    cause.message ?: "Error renaming element",
                    Unit,
                    ErrorCodes.InvalidParams,
                    cause
                )
            }
        }
    }

    return readAction {
        val edits = renamer.originals.map { (oldUrl, fileToOriginalText) ->
            val (file, original) = fileToOriginalText
            val uri = DocumentUri(oldUrl.asURI())
            val version = server.documents.getVersion(uri.uri)
                ?: 0 // According to LSP spec, it should be null, but our serialization would drop it, causing an error on the LSP side. Zero seems to work.
            val id = TextDocumentIdentifier(uri, version)
            val edits = computeTextEdits(original, file.text, context.granularity)
            TextDocumentEdit(id, edits)
        }

        // In `workspace/willRenameFiles` request, the rename of the file itself is handled on the client side.
        // Though we track it, we need to filter it out to avoid excessive data transfer and conflicts.
        val filteredChanges = server.fileChanges()
            .filterNot {
                when (it) {
                    is CreateFile -> isParentUri(context.uriToSkip, it.uri.uri)
                    is DeleteFile -> isParentUri(context.uriToSkip, it.uri.uri)
                    is RenameFile -> isParentUri(context.uriToSkip, it.oldUri.uri)
                    is TextDocumentEdit -> isParentUri(context.uriToSkip, it.textDocument.uri.uri)
                }
            }
        edits + filteredChanges
    }
}

private fun isParentUri(parent: URI?, candidate: URI): Boolean {
    val url = parent?.toFileUrl() ?: return false
    var candiate = candidate.toFileUrl()

    while (candiate != null) {
        if (url == candiate) return true
        candiate = candiate.parent
    }

    return false
}

private fun String.getPureName(extension: String) = removeSuffix(extension).trimEnd { it == '.' }

/**
 * Represents the difference in the name when `workspace/willRenameFiles` request occurs.
 */
class NameChange(
    val oldName: String,
    val newName: String
)

/**
 * Represents the data that is necessary to perform the rename operation in the language server
 * @param target the element on which the rename was invoked.
 * @param newName the new name to be applied to the target element.
 * @param granularity granularity with which difference between files should be calculated,
 * see [com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits].
 * @param uriToSkip path under which file operations should be ignored. This usually happens when `workspace/willRenameFiles` request
 * is called. IntelliJ engine will simulate the whole rename operation and possibly return the result including move of the files in the params.
 * Such changes should be ignored as they are handled by the client.
 */
class RenameContext(
    val target: PsiElement,
    val newName: String,
    val granularity: DiffGranularity,
    val uriToSkip: URI?
)
