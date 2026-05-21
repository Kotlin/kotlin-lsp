// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.rename

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.fileExtension
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.RenameFilesParams
import com.jetbrains.lsp.protocol.RenameParams
import com.jetbrains.lsp.protocol.WorkspaceEdit

object LSRename {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun rename(params: RenameParams): WorkspaceEdit? {
        return configuration.entriesFor<LSRenameProvider>(params.textDocument).firstNotNullOfOrNull { it.rename(params) }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun renameFile(params: RenameFilesParams): WorkspaceEdit? {
        val fileRename = params.files.singleOrNull() ?: return null

        return if (isDirectoryRename(fileRename)) {
            // Since it is unclear what language directory is renamed, it is up to callee to decide whether he should rename the directory or not.
            configuration.entries<LSRenameDirectoryProvider>().firstNotNullOfOrNull { it.renameDirectory(fileRename) }
        } else {
            configuration.entriesFor<LSRenameProvider>(fileRename.oldUri).firstNotNullOfOrNull { it.renameFile(fileRename) }
        }
    }

    private fun isDirectoryRename(rename: FileRename): Boolean {
        val oldUri = rename.oldUri
        val newUri = rename.newUri
        return oldUri.fileExtension == null && newUri.fileExtension == null
    }
}
