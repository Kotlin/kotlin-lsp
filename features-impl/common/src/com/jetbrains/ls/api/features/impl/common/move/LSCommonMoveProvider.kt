// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.fileName
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.utils.findDestination
import com.jetbrains.ls.api.features.move.LSMoveProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.RenameRequestType
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.WorkspaceEdit
import org.jetbrains.annotations.Nls

/**
 * Follows the logic of [com.intellij.refactoring.move.MoveHandler] but with the adaptation to the LSP.
 * @see MoveAnalysisResult
 */
internal object LSCommonMoveProvider : LSMoveProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveFile(params: List<FileRename>): WorkspaceEdit = doMove(params, false)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveDirectory(params: List<FileRename>): WorkspaceEdit = doMove(params, true)

    context(server: LSServer, handlerContext: LspHandlerContext)
    private suspend fun doMove(
        params: List<FileRename>,
        isOnlyDirectories: Boolean
    ): WorkspaceEdit {
        val changes = server.withWriteAnalysisContext {
            val result = readAction {
                val existedFile = params.find { it.newUri.findVirtualFile() != null }
                if (existedFile != null) return@readAction MoveAnalysisResult.Error(
                    LspServerBundle.message(
                        "error.move.file.exists.in.destination",
                        existedFile.newUri.fileName
                    )
                )

                val targetDirectory = findDestination(project, params)
                    ?: return@readAction MoveAnalysisResult.Error(LspServerBundle.message("error.move.destination.not.found"))

                val sources = params.map {
                    val vFile = it.oldUri.findVirtualFile() ?: return@readAction MoveAnalysisResult.Error(
                        LspServerBundle.message(
                            "error.move.file.not.found",
                            it.oldUri.fileName
                        )
                    )

                    when {
                        vFile.isDirectory -> vFile.findPsiDirectory(project)
                        !isOnlyDirectories -> vFile.findPsiFile(project)
                        else -> null
                    } ?: return@readAction MoveAnalysisResult.Error(LspServerBundle.message("error.move.file.not.found", it.oldUri.fileName))
                }

                val classified = sources.groupBy { it.name }
                if (classified.size != sources.size) {
                    val duplicate = classified.firstNotNullOf { (_, value) ->
                        if (value.size > 1) value.first().name else null
                    }

                    return@readAction MoveAnalysisResult.Error(LspServerBundle.message("error.move.files.with.same.name", duplicate))
                }

                val extensions = if (isOnlyDirectories) LSMoveHandlerDelegate.forDirectories() else LSMoveHandlerDelegate.forFiles()
                val candidates = sources.map { element ->
                    val modifiedElement = extensions.firstNotNullOfOrNull { it.prepareElementToMove(element) }
                    modifiedElement ?: element
                }.toTypedArray()

                val handler = extensions.find { it.canMove(candidates, targetDirectory) } ?: LSGenericMoveHandlerDelegate

                handler.createProcessor(candidates, targetDirectory)
            }

            when (result) {
                is MoveAnalysisResult.Error -> failMove(result.message)
                is MoveAnalysisResult.Success -> doRefactoring(
                    processor = result.processor,
                    granularity = TextEditsComputer.DiffGranularity.WORD,
                    uriToSkip = params.map { it.oldUri },
                    true
                )
            }
        }

        return WorkspaceEdit(documentChanges = changes)
    }

    context(_: LspHandlerContext)
    private suspend fun failMove(message: @Nls String): Nothing {
        lspClient.notify(
            ShowMessageNotificationType,
            ShowMessageParams(
                MessageType.Error,
                message = message,
            )
        )
        throwLspError(RenameRequestType, message, Unit, ErrorCodes.InvalidParams)
    }
}
