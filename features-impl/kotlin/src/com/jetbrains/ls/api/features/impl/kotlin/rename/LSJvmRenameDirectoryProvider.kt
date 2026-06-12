// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.rename

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiDirectory
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.impl.common.processors.RenameSingleDirectoryContext
import com.jetbrains.ls.api.features.impl.common.processors.createProcessor
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.rename.computeNameChange
import com.jetbrains.ls.api.features.rename.LSRenameDirectoryProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

internal object LSJvmRenameDirectoryProvider : LSRenameDirectoryProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun renameDirectory(params: FileRename): WorkspaceEdit? {
        return server.withWriteAnalysisContext {
            val context = readAction {
                if (params.newUri.findVirtualFile() != null) return@readAction null

                val nameChange = computeNameChange(params.oldUri, params.newUri, true) ?: return@readAction null
                val virtualFile = params.oldUri.findVirtualFile() ?: return@readAction null
                val directory = virtualFile.findPsiDirectory(project) ?: return@readAction null
                RenameSingleDirectoryContext(directory, nameChange.newName.fileName)
            } ?: return@withWriteAnalysisContext null

            val renamer = createProcessor(context) ?: return@withWriteAnalysisContext null
            doRefactoring(renamer, DiffGranularity.WORD, params.oldUri)
        }?.let { return WorkspaceEdit(documentChanges = it) }
    }
}
