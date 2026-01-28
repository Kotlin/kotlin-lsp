// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.rename

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlerContext
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
        return configuration.entriesFor<LSRenameProvider>(fileRename.oldUri).firstNotNullOfOrNull { it.renameFile(fileRename) }
    }
}
