// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.move

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.ls.api.features.impl.common.processors.LSRefactoringProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.utils.findDestination
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.move.LSMoveFileProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

/**
 * Follows the logic of [com.intellij.refactoring.move.MoveHandlerDelegate] but with the adaptation to the LSP.
 */
abstract class LSMoveFileProviderBase(override val supportedLanguages: Set<LSLanguage>) : LSMoveFileProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveFile(params: List<FileRename>): WorkspaceEdit? {
        val changes = server.withWriteAnalysisContext {
            val processor = readAction {
                val targetDirectory = findDestination(project, params) ?: return@readAction null


                val psiFiles = params.map {
                    val vFile = it.oldUri.findVirtualFile() ?: return@readAction null
                    vFile.findPsiFile(project) ?: return@readAction null
                }

                createProcessor(targetDirectory, psiFiles)
            } ?: return@withWriteAnalysisContext emptyList()


            doRefactoring(processor = processor, granularity = TextEditsComputer.DiffGranularity.WORD, uriToSkip = params.map { it.oldUri }, true)
        }

        return WorkspaceEdit(documentChanges = changes)
    }

    context(_: LSAnalysisContext)
    protected abstract fun createProcessor(targetDirectory: PsiDirectory, files: List<PsiFile>): LSRefactoringProcessor?
}
