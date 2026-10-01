// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSWorkspaceSymbolCustomizer
import com.jetbrains.ls.api.core.features.TYPE_SYMBOL_KINDS
import com.jetbrains.ls.api.core.features.WORKSPACE_SYMBOL_DATA_CONTRIBUTOR
import com.jetbrains.ls.api.core.features.WORKSPACE_SYMBOL_DATA_EXCLUDE_LIBRARIES
import com.jetbrains.ls.api.core.features.WORKSPACE_SYMBOL_DATA_INDEX
import com.jetbrains.ls.api.core.features.WORKSPACE_SYMBOL_DATA_NAME
import com.jetbrains.ls.api.core.features.WORKSPACE_SYMBOL_DATA_QUERY
import com.jetbrains.ls.api.core.features.lsContributeWorkspaceSymbols
import com.jetbrains.ls.api.core.features.requestedKinds
import com.jetbrains.ls.api.core.features.workspaceSymbolElement
import com.jetbrains.ls.api.core.features.workspaceSymbolItemsByName
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.symbols.LSWorkspaceSymbolProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

private val LOG = logger<LSWorkspaceSymbolProviderBase>()

abstract class LSWorkspaceSymbolProviderBase : LSWorkspaceSymbolProvider {
    abstract fun createCustomizer(): LSWorkspaceSymbolCustomizer

    context(server: LSServer, handlerContext: LspHandlerContext)
    final override fun getWorkspaceSymbols(params: WorkspaceSymbolParams): Flow<WorkspaceSymbol> = channelFlow {
        val customizer = createCustomizer()
        // URI-only locations only for a client that asks for the range on resolve.
        val lazyLocation = server.config.clientSupportsWorkspaceSymbolResolve
        server.withAnalysisContext {
            coroutineScope {
                for (contributor in customizer.getContributors(params.requestedKinds())) {
                    launch {
                        try {
                            lsContributeWorkspaceSymbols(project, customizer, contributor, params, lazyLocation).collect(::send)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LOG.warn("workspace/symbol contributor ${contributor.javaClass.name} failed", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds the item again by [WorkspaceSymbol.data] (contributor class, query, name, ordinal in the file, scope, see [lsContributeWorkspaceSymbols])
     * and gives the symbol its full location. Falls back to the first item of the same kind in the file.
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    final override suspend fun resolveWorkspaceSymbol(symbol: WorkspaceSymbol): WorkspaceSymbol? {
        val uri = (symbol.location as? WorkspaceSymbol.SymbolLocation.Partial)?.uri ?: return null
        val data = symbol.data as? JsonObject ?: return null
        val contributorClass = data[WORKSPACE_SYMBOL_DATA_CONTRIBUTOR]?.jsonPrimitive?.contentOrNull ?: return null
        val name = data[WORKSPACE_SYMBOL_DATA_NAME]?.jsonPrimitive?.contentOrNull ?: return null
        val query = data[WORKSPACE_SYMBOL_DATA_QUERY]?.jsonPrimitive?.contentOrNull ?: return null
        val index = data[WORKSPACE_SYMBOL_DATA_INDEX]?.jsonPrimitive?.intOrNull ?: return null
        val excludeLibraries = data[WORKSPACE_SYMBOL_DATA_EXCLUDE_LIBRARIES]?.jsonPrimitive?.booleanOrNull == true
        val customizer = createCustomizer()
        // The class-kind contributors may be outside the default list (Go).
        val contributor = (customizer.getContributors() + customizer.getContributors(TYPE_SYMBOL_KINDS))
            .firstOrNull { it.javaClass.name == contributorClass } ?: return null
        return server.withAnalysisContext {
            readAction {
                // Count as lsContributeWorkspaceSymbols does: every item of the name in its file, before any filter.
                val itemsInFile = workspaceSymbolItemsByName(project, contributor, name, query, excludeLibraries).filter { item ->
                    item.workspaceSymbolElement()?.containingFile?.virtualFile?.let { DocumentUri(it.uri) } == uri
                }
                fun symbolOfSameKind(item: NavigationItem): WorkspaceSymbol? =
                    customizer.createWorkspaceSymbol(item, contributor)?.takeIf { it.kind == symbol.kind }
                val fresh = itemsInFile.getOrNull(index)?.let(::symbolOfSameKind)
                            ?: itemsInFile.firstNotNullOfOrNull(::symbolOfSameKind)
                fresh?.let { symbol.copy(location = it.location) }
            }
        }
    }
}
