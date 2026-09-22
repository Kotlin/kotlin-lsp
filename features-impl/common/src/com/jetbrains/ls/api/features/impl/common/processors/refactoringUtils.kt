// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.processors

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.jetbrains.analyzer.api.FileUrl
import com.jetbrains.analyzer.api.fileUrl
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.processors.LSBaseRefactoringProcessor
import com.jetbrains.ls.api.core.processors.planRefactoring
import com.jetbrains.ls.api.core.processors.writeRefactoring
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.ls.api.features.textEdits.fileChanges
import com.jetbrains.ls.snapshot.api.impl.core.asURI
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import com.jetbrains.lsp.implementation.LspException
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.FileChange
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.RenameRequestType
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * See JavaDoc of [doRefactoring] overload.
 */
context(server: LSServer, _: LSAnalysisContext, _: LspHandlerContext)
suspend fun doRefactoring(
    processor: LSBaseRefactoringProcessor,
    granularity: DiffGranularity,
    uriToSkip: URI?,
    showNotificationWithError : Boolean
): List<FileChange> = doRefactoring(processor, granularity, listOfNotNull(uriToSkip), showNotificationWithError)

/**
 * Executes [com.jetbrains.ls.api.core.processors.LSRefactoringProcessor], and returns diff after its changes
 *
 * @param granularity granularity with which difference between files should be calculated,
 *  see [com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits].
 * @param uriToSkip paths under which file operations should be ignored. This usually happens when
 *  `workspace/willRenameFiles` request is called. IntelliJ engine will simulate the whole refactoring
 *  operation and possibly return the result, including move of the files in the params.
 *  Such changes should be ignored as they are handled by the client.
 *  @param showNotificationWithError whether to send a notification to the client in case of error occurred.
 */
context(server: LSServer, _: LSAnalysisContext, _: LspHandlerContext)
suspend fun doRefactoring(
    processor: LSBaseRefactoringProcessor,
    granularity: DiffGranularity,
    uriToSkip: List<URI>,
    showNotificationWithError : Boolean
): List<FileChange> {
    val originals = try {
        withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                executeRefactoringProcessor(project, processor)
            }
        }
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Throwable) {
        failRefactoring(ex, showNotificationWithError)
    }

    return computeRefactoringChanges(originals, granularity, uriToSkip)
}

/** Converts a refactoring failure into an LSP error. */
context(_: LspHandlerContext)
internal suspend fun failRefactoring(ex: Throwable, showNotificationWithError: Boolean): Nothing {
    rethrowControlFlowException(ex)
    when (ex) {
        is LspException -> throw ex
        else -> {
            val cause = refactoringErrorCause(ex)

            if (showNotificationWithError) {
                lspClient.notify(
                    ShowMessageNotificationType,
                    ShowMessageParams(
                        MessageType.Error,
                        cause.message ?: LspServerBundle.message("error.performing.refactoring")
                    )
                )
            }

            throwLspError(
                RenameRequestType,
                cause.message ?: LspServerBundle.message("error.performing.refactoring"),
                Unit,
                ErrorCodes.InvalidParams,
                cause
            )
        }
    }
}

/** Prefers the first [IncorrectOperationException] in the cause chain. It carries the readable message. */
internal fun refactoringErrorCause(ex: Throwable): Throwable =
    generateSequence(ex) { it.cause?.takeIf { c -> c != it } }
        .filterIsInstance<IncorrectOperationException>()
        .firstOrNull() ?: ex

/** Computes the response changes after a refactoring: text edits against [originals] plus the tracked file operations. */
context(server: LSServer, _: LSAnalysisContext)
internal suspend fun computeRefactoringChanges(
    originals: Map<FileUrl, Pair<PsiFile, String>>,
    granularity: DiffGranularity,
    urisToSkip: List<URI>,
): List<FileChange> {
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
                    is CreateFile -> urisToSkip.any { uriToSkip -> isParentUri(uriToSkip, it.uri.uri) }
                    is DeleteFile -> urisToSkip.any { uriToSkip -> isParentUri(uriToSkip, it.uri.uri) }
                    is RenameFile -> urisToSkip.any { uriToSkip -> isParentUri(uriToSkip, it.oldUri.uri) }
                    is TextDocumentEdit -> urisToSkip.any { uriToSkip -> isParentUri(uriToSkip, it.textDocument.uri.uri) }
                }
            }
        edits + filteredChanges
    }
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

/**
 * Executes logic of [com.intellij.refactoring.BaseRefactoringProcessor] in simplified way without showing UI.
 *
 * It returns the URL and the text of each file of [LSBaseRefactoringProcessor.getFilesToSave], before
 * the refactoring writes. The map is empty when the refactoring changes nothing.
 */
fun executeRefactoringProcessor(project: Project, processor: LSBaseRefactoringProcessor): Map<FileUrl, Pair<PsiFile, String>> {
    val usages = planRefactoring(project, processor) ?: return emptyMap()
    return startRefactoring(processor, usages) {
        writeRefactoring(project, processor, usages)
    }
}

private fun startRefactoring(
    processor: LSBaseRefactoringProcessor,
    usages: Array<UsageInfo>,
    callback: () -> Unit,
): Map<FileUrl, Pair<PsiFile, String>> {
    val originals = saveFileTexts(processor, usages)
    callback()
    return originals
}

/** The URL and the text of each file to save, before the refactoring writes. */
private fun saveFileTexts(processor: LSBaseRefactoringProcessor, usages: Array<UsageInfo>): Map<FileUrl, Pair<PsiFile, String>> {
    return processor.getFilesToSave(usages)
        .mapNotNull { file -> file.virtualFile?.let { file to it.fileUrl } }
        .distinctBy { it.second }
        .associate { (file, fileUrl) -> fileUrl to (file to file.text) }
}
