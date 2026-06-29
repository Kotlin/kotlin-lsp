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
import com.jetbrains.ls.api.features.impl.common.processors.RefactoringProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.move.LSMoveFileProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

abstract class LSMoveFileProviderBase(override val supportedLanguages: Set<LSLanguage>) : LSMoveFileProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveFile(params: FileRename): WorkspaceEdit? {
        val changes = server.withWriteAnalysisContext {
            val processor = readAction {
                val newDestination = params.newUri.findVirtualFile()
                if (newDestination != null) return@readAction null

                val newPath = params.newUri.toPath() ?: return@readAction null
                val targetPath = newPath.parent
                val targetVFile = VirtualFileManager.getInstance().findFileByNioPath(targetPath) ?: return@readAction null
                val targetDirectory = targetVFile.findPsiDirectory(project)  ?: return@readAction null
                val virtualFile = params.oldUri.findVirtualFile() ?: return@readAction null
                val file = virtualFile.findPsiFile(project) ?: return@readAction null

                createProcessor(targetDirectory, file)
            } ?: return@withWriteAnalysisContext emptyList()


            doRefactoring(processor = processor, granularity = TextEditsComputer.DiffGranularity.WORD, uriToSkip = params.oldUri, true)
        }

        return WorkspaceEdit(documentChanges = changes)
    }

    context(_: LSAnalysisContext)
    protected abstract fun createProcessor(targetDirectory: PsiDirectory, file: PsiFile): RefactoringProcessor?
}
