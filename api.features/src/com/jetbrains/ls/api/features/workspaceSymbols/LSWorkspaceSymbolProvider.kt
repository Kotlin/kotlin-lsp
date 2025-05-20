package com.jetbrains.ls.api.features.workspaceSymbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.flow.Flow

interface LSWorkspaceSymbolProvider : LSConfigurationEntry {
    context(LSServer)
    fun getWorkspaceSymbols(params: WorkspaceSymbolParams): Flow<WorkspaceSymbol>
}