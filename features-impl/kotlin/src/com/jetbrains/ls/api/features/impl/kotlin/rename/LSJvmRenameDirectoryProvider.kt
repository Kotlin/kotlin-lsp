// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.rename

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiDirectory
import com.intellij.psi.JavaDirectoryService
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.impl.common.rename.RenameContext
import com.jetbrains.ls.api.features.impl.common.rename.computeNameChange
import com.jetbrains.ls.api.features.impl.common.rename.doRename
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
                val pkg = JavaDirectoryService.getInstance().getPackageInSources(directory) ?: return@readAction null
                RenameContext(pkg, nameChange.newName, DiffGranularity.WORD, params.oldUri)
            } ?: return@withWriteAnalysisContext null

            doRename(context)
        }?.let { return WorkspaceEdit(documentChanges = it) }
    }
}
