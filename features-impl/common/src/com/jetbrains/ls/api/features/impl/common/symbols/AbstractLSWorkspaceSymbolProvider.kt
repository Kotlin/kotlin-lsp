// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.runReadAction
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.symbols.LSWorkspaceSymbolProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

abstract class AbstractLSWorkspaceSymbolProvider : LSWorkspaceSymbolProvider {
    abstract fun getContributors(): List<ChooseByNameContributor>

    context(_: LSServer, _: LSAnalysisContext)
    abstract fun createWorkspaceSymbol(item: NavigationItem, contributor: ChooseByNameContributor): WorkspaceSymbol?

    context(_: LSServer, _: LspHandlerContext)
    final override fun getWorkspaceSymbols(params: WorkspaceSymbolParams): Flow<WorkspaceSymbol> = channelFlow {
        withAnalysisContext {
            coroutineScope {
                for (contributor in getContributors()) {
                    launch { handleContributor(contributor, params.query, channel) }
                }
            }
        }
    }

    context(_: LSServer, _: LSAnalysisContext)
    private suspend fun handleContributor(
        contributor: ChooseByNameContributor,
        query: String,
        channel: SendChannel<WorkspaceSymbol>
    ) {
        val names = runReadAction { contributor.getNames(project, /* includeNonProjectItems = */ false) }
        if (names.isEmpty()) return
        for (name in names) {
            if (query.isEmpty() || name.contains(query, ignoreCase = true)/*TODO some fancy fuzzy search should probably be here*/) {
                val items = runReadAction { contributor.getItemsByName(name, query, project, /* includeNonProjectItems = */ false) }
                for (item in items) {
                    runReadAction { createWorkspaceSymbol(item, contributor) }?.let { channel.send(it) }
                }
            }
        }
    }
}