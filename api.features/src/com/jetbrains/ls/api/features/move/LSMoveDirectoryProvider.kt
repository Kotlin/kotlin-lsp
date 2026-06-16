// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.move

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.WorkspaceEdit

interface LSMoveDirectoryProvider : LSConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun moveDirectory(params: FileRename): WorkspaceEdit?
}