// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ChooseByNameContributorEx2
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.util.indexing.FindSymbolParameters
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.symbols.LSWorkspaceSymbolProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

abstract class LSWorkspaceSymbolProviderBase : LSWorkspaceSymbolProvider {
    abstract fun getContributors(): List<ChooseByNameContributor>

    context(server: LSServer, analysisContext: LSAnalysisContext)
    abstract fun createWorkspaceSymbol(item: NavigationItem, contributor: ChooseByNameContributor, qualifiedQuery: Boolean = false): WorkspaceSymbol?

    context(server: LSServer, handlerContext: LspHandlerContext)
    final override fun getWorkspaceSymbols(params: WorkspaceSymbolParams): Flow<WorkspaceSymbol> = channelFlow {
        server.withAnalysisContext {
            coroutineScope {
                for (contributor in getContributors()) {
                    launch { handleContributor(contributor, params.query, channel) }
                }
            }
        }
    }

    context(server: LSServer, analysisContext: LSAnalysisContext)
    private suspend fun handleContributor(
        contributor: ChooseByNameContributor,
        query: String,
        channel: SendChannel<WorkspaceSymbol>
    ) {
        var qualifiedName: String? = null
        var shortName: String = query
        if (contributor is GotoClassContributor) {
            val separators = listOf(".") + listOfNotNull(contributor.qualifiedNameSeparator)
            for (separator in separators) {
                val idx = query.lastIndexOf(separator)
                if (idx >= 0) {
                    qualifiedName = query
                    shortName = query.substring(idx + separator.length)
                    break
                }
            }
        }
        val searchScope = FindSymbolParameters.searchScopeFor(project, /* searchInLibraries = */ false)
        val parameters = FindSymbolParameters(query, shortName, searchScope)
        // Mirrors ContributorsBasedGotoByModel.doProcessContributorNames: dispatch on Ex2/Ex/base,
        // collecting into a set to deduplicate names that appear in multiple indices
        // (e.g. a field name present in both FIELDS and RECORD_COMPONENTS for Java records).
        val names: Set<String> = readAction {
            buildSet {
                when (contributor) {
                    is ChooseByNameContributorEx2 -> contributor.processNames({ add(it); true }, parameters)
                    is ChooseByNameContributorEx -> contributor.processNames({ add(it); true }, searchScope, /* filter = */ null)
                    else -> addAll(contributor.getNames(project, /* includeNonProjectItems = */ false))
                }
            }
        }
        if (names.isEmpty()) return
        for (name in names) {
            if (shortName.isEmpty() || name.contains(shortName, ignoreCase = true)) {
                val items = readAction {
                    val result = mutableListOf<NavigationItem>()
                    when (contributor) {
                        is ChooseByNameContributorEx -> contributor.processElementsWithName(name, result::add, parameters)
                        else -> result.addAll(contributor.getItemsByName(name, shortName, project, /* includeNonProjectItems = */ false))
                    }
                    if (qualifiedName != null && contributor is GotoClassContributor) {
                        result.filter { contributor.getQualifiedName(it)?.contains(qualifiedName, ignoreCase = true) == true }
                    } else {
                        result
                    }
                }
                for (item in items) {
                    readAction { createWorkspaceSymbol(item, contributor, qualifiedName != null) }?.let { channel.send(it) }
                }
            }
        }
    }
}