// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.flow.Flow

interface LSWorkspaceSymbolProvider : LSConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    fun getWorkspaceSymbols(params: WorkspaceSymbolParams): Flow<WorkspaceSymbol>
}
