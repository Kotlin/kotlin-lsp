// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.psi.impl.file.PsiPackageImplUtil
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toPath
import com.jetbrains.ls.api.features.impl.common.processors.MoveSingleDirectoryContext
import com.jetbrains.ls.api.features.impl.common.processors.createProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.move.LSMoveDirectoryProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

internal object LSJvmMoveDirectoryProvider: LSMoveDirectoryProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveDirectory(params: FileRename): WorkspaceEdit? {
        return server.withWriteAnalysisContext {
            val context = readAction {
                val newDestination = params.newUri.findVirtualFile()
                if (newDestination != null) return@readAction null

                val newPath = params.newUri.toPath() ?: return@readAction null
                val targetPath = newPath.parent ?: return@readAction null
                val targetVFile = VirtualFileManager.getInstance().findFileByNioPath(targetPath) ?: return@readAction null
                val targetDirectory = targetVFile.findPsiDirectory(project) ?: return@readAction null

                val sourceVFile = params.oldUri.findVirtualFile() ?: return@readAction null
                val sourceDirectory = sourceVFile.findPsiDirectory(project) ?: return@readAction null
                if (!PsiPackageImplUtil.isDirectoryUnderPackage(sourceDirectory)) return@readAction null

                MoveSingleDirectoryContext(targetDirectory, sourceDirectory)
            } ?: return@withWriteAnalysisContext null

            val processor = createProcessor(context) ?: return@withWriteAnalysisContext null
            doRefactoring(processor, TextEditsComputer.DiffGranularity.WORD, params.oldUri)
        }?.let { return WorkspaceEdit(documentChanges = it) }
    }
}
