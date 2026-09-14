// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.utils.findDestination
import com.jetbrains.ls.api.features.move.LSMoveProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

/**
 * Follows the logic of [com.intellij.refactoring.move.MoveHandler] but with the adaptation to the LSP.
 */
internal object LSCommonMoveProvider : LSMoveProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveFile(params: List<FileRename>): WorkspaceEdit? = doMove(params, false)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveDirectory(params: List<FileRename>): WorkspaceEdit? = doMove(params, true)

    context(server: LSServer, handlerContext: LspHandlerContext)
    private suspend fun doMove(
        params: List<FileRename>,
        isOnlyDirectories : Boolean
    ): WorkspaceEdit? {
        val changes = server.withWriteAnalysisContext {
            val processor = readAction {
                val targetDirectory = findDestination(project, params) ?: return@readAction null

                val sources = params.map {
                    val vFile = it.oldUri.findVirtualFile() ?: return@readAction null

                    when {
                        vFile.isDirectory -> vFile.findPsiDirectory(project)
                        !isOnlyDirectories -> vFile.findPsiFile(project)
                        else -> null
                    } ?: return@readAction null
                }

                if (sources.distinctBy { it.name }.size != sources.size) return@readAction null

                val extensions = if (isOnlyDirectories) LSMoveHandlerDelegate.forDirectories() else LSMoveHandlerDelegate.forFiles()
                val candidates = sources.map { element ->
                    val modifiedElement = extensions.firstNotNullOfOrNull { it.prepareElementToMove(element) }
                    modifiedElement ?: element
                }.toTypedArray()

                val handler = extensions.find { it.canMove(candidates, targetDirectory) } ?: LSGenericMoveHandlerDelegate

                handler.createProcessor(candidates, targetDirectory)
            } ?: return@withWriteAnalysisContext null


            doRefactoring(processor = processor, granularity = TextEditsComputer.DiffGranularity.WORD, uriToSkip = params.map { it.oldUri }, true)
        } ?: return null

        return WorkspaceEdit(documentChanges = changes)
    }
}
