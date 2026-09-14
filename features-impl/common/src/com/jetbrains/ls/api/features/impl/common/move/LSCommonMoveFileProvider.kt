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
import com.jetbrains.ls.api.features.move.LSMoveFileProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

/**
 * Follows the logic of [com.intellij.refactoring.move.MoveHandler] but with the adaptation to the LSP.
 */
internal object LSCommonMoveFileProvider : LSMoveFileProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveFile(params: List<FileRename>): WorkspaceEdit? {
        val changes = server.withWriteAnalysisContext {
            val processor = readAction {
                val targetDirectory = findDestination(project, params) ?: return@readAction null

                val sources = params.map {
                    val vFile = it.oldUri.findVirtualFile() ?: return@readAction null

                    if (vFile.isDirectory) {
                        vFile.findPsiDirectory(project)
                    } else {
                        vFile.findPsiFile(project)
                    } ?: return@readAction null
                }

                if (sources.distinctBy { it.name }.size != sources.size) return@readAction null

                val candidates = sources.map { element ->
                    val modifiedElement = LSMoveHandlerDelegate.EP_NAME.extensionList.firstNotNullOfOrNull { it.prepareElementToMove(element) }
                    modifiedElement ?: element
                }.toTypedArray()

                val handler = LSMoveHandlerDelegate.EP_NAME.extensionList.find { it.canMove(candidates, targetDirectory) }
                        ?: LSGenericMoveHandlerDelegate

                handler.createProcessor(candidates, targetDirectory)
            } ?: return@withWriteAnalysisContext null


            doRefactoring(processor = processor, granularity = TextEditsComputer.DiffGranularity.WORD, uriToSkip = params.map { it.oldUri }, true)
        } ?: return null

        return WorkspaceEdit(documentChanges = changes)
    }
}
