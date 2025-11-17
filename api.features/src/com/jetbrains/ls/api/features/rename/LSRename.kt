// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.rename

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.RenameParams
import com.jetbrains.lsp.protocol.WorkspaceEdit

object LSRename {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun rename(params: RenameParams): WorkspaceEdit? {
        return entriesFor<LSRenameProvider>(params.textDocument).firstNotNullOfOrNull { it.rename(params) }
    }
}