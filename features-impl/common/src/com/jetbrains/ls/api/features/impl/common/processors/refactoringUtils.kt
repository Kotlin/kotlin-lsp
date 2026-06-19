// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.processors

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDirectoryContainer
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes [RefactoringProcessor], and returns diff after its changes
 *
 * @param granularity granularity with which difference between files should be calculated,
 *  see [com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits].
 * @param uriToSkip path under which file operations should be ignored. This usually happens when
 *  `workspace/willRenameFiles` request is called. IntelliJ engine will simulate the whole rename
 *  operation and possibly return the result including move of the files in the params.
 *  Such changes should be ignored as they are handled by the client.
 */
context(server: LSServer, _: LSAnalysisContext)
suspend fun doRefactoring(
    processor: RefactoringProcessor,
    granularity: DiffGranularity,
    uriToSkip: URI?,
): List<FileChange> {
    val originals = try {
        withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                execute(processor)
            }
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
                    cause.message ?: "Error performing refactoring",
                    Unit,
                    ErrorCodes.InvalidParams,
                    cause
                )
            }
        }
    }

    return readAction {
        val edits = originals.mapNotNull { (oldUrl, fileToOriginalText) ->
            val (file, original) = fileToOriginalText

            val uri = DocumentUri(oldUrl.asURI())
            val version = server.documents.getVersion(uri.uri)
                ?: 0 // According to LSP spec, it should be null, but our serialization would drop it, causing an error on the LSP side. Zero seems to work.
            val id = TextDocumentIdentifier(uri, version)
            val textEdits = computeTextEdits(original, file.text, granularity)
            TextDocumentEdit(id, textEdits)
        }

        // In `workspace/willRenameFiles` request, the rename of the file/directory itself is handled
        // on the client side. Though we track it, we need to filter it out to avoid excessive data
        // transfer and conflicts.
        val filteredChanges = server.fileChanges()
            .filterNot {
                when (it) {
                    is CreateFile -> isParentUri(uriToSkip, it.uri.uri)
                    is DeleteFile -> isParentUri(uriToSkip, it.uri.uri)
                    is RenameFile -> isParentUri(uriToSkip, it.oldUri.uri)
                    is TextDocumentEdit -> isParentUri(uriToSkip, it.textDocument.uri.uri)
                }
            }
        edits + filteredChanges
    }
}

fun createProcessor(context: RefactoringContext): RefactoringProcessor? = when (context) {
    is RenameContext -> Renamer.create(context)
    is RenameSingleDirectoryContext -> DirectoryMover.create(context)
    is MoveSingleDirectoryContext -> DirectoryMover.create(context)
    else -> throw IllegalArgumentException("Unknown refactoring context: $context")
}

@RequiresEdt
internal fun <T> runReadActionInBgt(project: Project, action: () -> T): T {
    return runWithModalProgressBlocking(project, "") {
        try {
            Result.success(readAction(action))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }.getOrThrow()
}

private fun isParentUri(parent: URI?, candidate: URI): Boolean {
    val url = parent?.toFileUrl() ?: return false
    var candidateUrl = candidate.toFileUrl()
    while (candidateUrl != null) {
        if (url == candidateUrl) return true
        candidateUrl = candidateUrl.parent
    }
    return false
}

fun PsiDirectoryContainer.findDirectoryInSameSourceRoot(contextFile: PsiFile): PsiDirectory? {
    val contextVirtualFile = contextFile.virtualFile ?: return null
    val fileIndex = ProjectFileIndex.getInstance(contextFile.project)
    val sourceRoot = fileIndex.getSourceRootForFile(contextVirtualFile) ?: return null
    return directories.firstOrNull { directory ->
        fileIndex.getSourceRootForFile(directory.virtualFile) == sourceRoot
    }
}
