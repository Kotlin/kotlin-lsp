// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ChooseByNameContributorEx2
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.util.Processor
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
        if (query.isBlank()) return
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
        val searchScope = FindSymbolParameters.searchScopeFor(project, /* searchInLibraries = */ true)
        val parameters = FindSymbolParameters(query, shortName, searchScope)
        // Mirrors ContributorsBasedGotoByModel.doProcessContributorNames: dispatch on Ex2/Ex/base,
        // collecting into a set to deduplicate names that appear in multiple indices
        // (e.g. a field name present in both FIELDS and RECORD_COMPONENTS for Java records).
        val names: Set<String> = readAction {
            val collector = NameCollector(shortName)
            when (contributor) {
                is ChooseByNameContributorEx2 -> {
                    contributor.processNames(collector, parameters)
                    collector.set
                }

                is ChooseByNameContributorEx -> {
                    contributor.processNames(collector, searchScope, /* filter = */ null)
                    collector.set
                }

                else -> contributor.getNames(project, /* includeNonProjectItems = */ true)
                    .filter { name -> isApplicableName(name, shortName) }
                    .toSet()
            }
        }
        for (name in names) {
            val items = readAction {
                val result = mutableListOf<NavigationItem>()
                when (contributor) {
                    is ChooseByNameContributorEx -> contributor.processElementsWithName(name, result::add, parameters)
                    else -> result.addAll(contributor.getItemsByName(name, shortName, project, /* includeNonProjectItems = */ true))
                }
                if (qualifiedName != null && contributor is GotoClassContributor) {
                    result.filter { contributor.getQualifiedName(it)?.contains(qualifiedName, ignoreCase = true) == true }
                } else {
                    result
                }
            }
            for (item in items) {
                readAction { createWorkspaceSymbol(item, contributor, qualifiedName != null) }?.let {
                    channel.send(it)
                }
            }
        }
    }

    private class NameCollector(private val shortName: String) : Processor<String> {
        val set = mutableSetOf<String>()

        override fun process(name: String?): Boolean {
            if (name == null) return true
            if (isApplicableName(name, shortName)) {
                set.add(name)
            }
            return true
        }
    }

    companion object {
        private fun isApplicableName(name: String, shortName: String) = shortName.isEmpty() || name.contains(shortName, ignoreCase = true)
    }
}