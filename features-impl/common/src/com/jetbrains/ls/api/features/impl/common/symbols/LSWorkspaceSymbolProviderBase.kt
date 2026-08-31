// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSWorkspaceSymbolCustomizer
import com.jetbrains.ls.api.core.features.lsContributeWorkspaceSymbols
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.symbols.LSWorkspaceSymbolProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private val LOG = logger<LSWorkspaceSymbolProviderBase>()

abstract class LSWorkspaceSymbolProviderBase : LSWorkspaceSymbolProvider {
    abstract fun createCustomizer(): LSWorkspaceSymbolCustomizer

    context(server: LSServer, handlerContext: LspHandlerContext)
    final override fun getWorkspaceSymbols(params: WorkspaceSymbolParams): Flow<WorkspaceSymbol> = channelFlow {
        val customizer = createCustomizer()
        server.withAnalysisContext {
            coroutineScope {
                for (contributor in customizer.getContributors()) {
                    launch {
                        try {
                            lsContributeWorkspaceSymbols(project, customizer, contributor, params, channel::send)
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
}
