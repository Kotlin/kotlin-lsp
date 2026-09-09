// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.move

import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiDirectory
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.impl.common.processors.MoveDirectoryContext
import com.jetbrains.ls.api.features.impl.common.processors.createProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.utils.findDestination
import com.jetbrains.ls.api.features.move.LSMoveDirectoryProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

internal object LSJvmMoveDirectoryProvider: LSMoveDirectoryProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun moveDirectory(params: List<FileRename>): WorkspaceEdit? {
        return server.withWriteAnalysisContext {
            val processor = readAction {
                val targetDirectory = findDestination(project, params)  ?: return@readAction null

                val sourceDirectories = params.map { param ->
                    val sourceVFile = param.oldUri.findVirtualFile() ?: return@readAction null
                    val sourceDirectory = sourceVFile.findPsiDirectory(project) ?: return@readAction null
                    if (!PackageUtil.isDirectoryUnderPackage(sourceDirectory)) return@readAction null
                    sourceDirectory
                }

                val context = MoveDirectoryContext(targetDirectory, sourceDirectories.toTypedArray())
                createProcessor(context)
            } ?: return@withWriteAnalysisContext null

            doRefactoring(processor, TextEditsComputer.DiffGranularity.WORD, params.map { it.oldUri }, true)
        }?.let { return WorkspaceEdit(documentChanges = it) }
    }
}
