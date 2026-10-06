// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSInjectedFile
import com.jetbrains.ls.api.core.features.hostRanges
import com.jetbrains.ls.api.core.features.lsCollectInjectedFiles
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.ls.api.features.semanticTokens.encoding.SemanticTokensEncoder
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensParams
import com.jetbrains.lsp.protocol.SemanticTokensRangeParams
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

// TODO LSP-236 send partial results here
object LSSemanticTokens {
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
        val providers = configuration.entriesFor<LSSemanticTokensProvider>(params.textDocument)
        val hostTokens = providers.flatMap { it.full(params) }
        val result = withInjectedTokens(params.textDocument, hostTokens, range = null)
        val registry = createRegistry()
        val encoded = SemanticTokensEncoder.encode(result, registry)
        return SemanticTokens(resultId = null, data = encoded)
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun semanticTokensRange(params: SemanticTokensRangeParams): SemanticTokens {
        val providers = configuration.entriesFor<LSSemanticTokensProvider>(params.textDocument)
        val hostTokens = providers.flatMap { it.range(params) }
        val result = withInjectedTokens(params.textDocument, hostTokens, params.range)
        val registry = createRegistry()
        val encoded = SemanticTokensEncoder.encode(result, registry)
        return SemanticTokens(resultId = null, data = encoded)
    }

    context(configuration: LSConfiguration)
    fun createRegistry(): LSSemanticTokenRegistry {
        val registries = configuration.entries<LSSemanticTokensProvider>().map { it.createRegistry() }
        if (registries.isEmpty()) return LSSemanticTokenRegistry.EMPTY
        if (registries.size == 1) return registries.first()
        return LSSemanticTokenRegistry(
            registries.flatMap { it.types }.distinct(),
            registries.flatMap { it.modifiers }.distinct(),
        )
    }

    /**
     * The tokens of one injected file, with ranges in its [LSInjectedFile.documentWindow] (injected) coordinates.
     * Reuses the LS providers of the injected language that extend [LSSemanticTokensProviderBase]. Without such a provider,
     * takes the first non-empty [LSInjectedSemanticTokensFallback] result, limited to the types of the advertised legend.
     * Requires read access; checks [job].
     */
    context(server: LSServer, configuration: LSConfiguration)
    private fun injectedFileTokens(injected: LSInjectedFile, fallbacks: InjectedTokensFallbacks, job: Job): List<LSSemanticTokenWithRange> {
        val language = configuration.languageFor(injected.psiFile.language)
        val providers = language?.let { configuration.entriesFor<LSSemanticTokensProviderBase>(it) }.orEmpty()
        if (providers.isNotEmpty()) {
            return providers.flatMap { provider -> provider.tokensFor(injected.psiFile, injected.documentWindow, documentRange = null) }
        }
        for (fallback in fallbacks.fallbacks) {
            job.ensureActive()
            val tokens = fallback.tokens(injected, job).filter { it.token.type in fallbacks.legendTypes }
            if (tokens.isNotEmpty()) return tokens
        }
        return emptyList()
    }

    /** The [LSInjectedSemanticTokensFallback] entries and the token types of the advertised legend, computed once per request. */
    private class InjectedTokensFallbacks(val fallbacks: List<LSInjectedSemanticTokensFallback>, val legendTypes: Set<LSSemanticTokenType>)

    context(configuration: LSConfiguration)
    private fun injectedTokensFallbacks(): InjectedTokensFallbacks {
        val fallbacks = configuration.entries<LSInjectedSemanticTokensFallback>()
        return InjectedTokensFallbacks(fallbacks, if (fallbacks.isEmpty()) emptySet() else createRegistry().types.toSet())
    }

    /**
     * Adds the tokens of the injections that intersect [range] (`null` is the whole file) at their host positions, and drops the
     * [hostTokens] that overlap the host ranges of an injection that has tokens.
     */
    context(server: LSServer, configuration: LSConfiguration)
    private suspend fun withInjectedTokens(
        textDocument: TextDocumentIdentifier,
        hostTokens: List<LSSemanticTokenWithRange>,
        range: Range?,
    ): List<LSSemanticTokenWithRange> {
        val job = currentCoroutineContext().job
        return server.withAnalysisContext {
            readAction {
                val virtualFile = textDocument.findVirtualFile() ?: return@readAction hostTokens
                val hostFile = virtualFile.findPsiFile(project) ?: return@readAction hostTokens
                val hostDocument = virtualFile.findDocument() ?: return@readAction hostTokens
                val requestRange = range?.toTextRange(hostDocument)
                val fallbacks = injectedTokensFallbacks()
                val injections = lsCollectInjectedFiles(hostFile, job, requestRange).mapNotNull { injected ->
                    injectedTokensAtHost(injected, hostDocument, requestRange, fallbacks, job)
                }
                if (injections.isEmpty()) return@readAction hostTokens
                val injectedHostRanges = mergeOverlapping(injections.flatMap { it.hostRanges })
                val keptHostTokens = hostTokens.filter { token ->
                    !intersectsStrictAny(injectedHostRanges, token.range.toTextRange(hostDocument))
                }
                keptHostTokens + injections.flatMap { it.tokens }
            }
        }
    }

    private class InjectedTokensAtHost(val hostRanges: List<TextRange>, val tokens: List<LSSemanticTokenWithRange>)

    /** Null when the window is invalid, misses [requestRange], or has no token there. */
    context(server: LSServer, configuration: LSConfiguration)
    private fun injectedTokensAtHost(
        injected: LSInjectedFile,
        hostDocument: Document,
        requestRange: TextRange?,
        fallbacks: InjectedTokensFallbacks,
        job: Job,
    ): InjectedTokensAtHost? {
        job.ensureActive()
        val window = injected.documentWindow
        if (!window.isValid) return null
        val hostRanges = window.hostRanges.map { TextRange.create(it) }.filter { !it.isEmpty }
        if (requestRange != null && hostRanges.none { it.intersectsStrict(requestRange) }) return null
        val tokens = injectedFileTokens(injected, fallbacks, job).flatMap { token ->
            injected.hostRanges(token.range.toTextRange(window))
                .filter { requestRange == null || it.intersectsStrict(requestRange) }
                .map { LSSemanticTokenWithRange(token.token, it.toLspRange(hostDocument)) }
        }
        if (tokens.isEmpty()) return null
        return InjectedTokensAtHost(hostRanges, tokens)
    }

    /**
     * Sorts non-empty [ranges] and merges the ones that overlap strictly; touching ranges stay apart.
     * A non-empty range then strictly intersects a result range exactly when it strictly intersects one of [ranges].
     */
    private fun mergeOverlapping(ranges: List<TextRange>): List<TextRange> {
        val merged = ArrayList<TextRange>(ranges.size)
        for (range in ranges.sortedBy { it.startOffset }) {
            val last = merged.lastOrNull()
            if (last != null && range.startOffset < last.endOffset) {
                merged[merged.lastIndex] = TextRange(last.startOffset, maxOf(last.endOffset, range.endOffset))
            }
            else {
                merged += range
            }
        }
        return merged
    }

    /** Whether [range] strictly intersects one of [sorted]: disjoint ranges, sorted by start, as [mergeOverlapping] makes them. */
    private fun intersectsStrictAny(sorted: List<TextRange>, range: TextRange): Boolean {
        // the first range that ends after the start of [range]; the ones before it cannot intersect [range] strictly
        val index = sorted.binarySearch { if (it.endOffset <= range.startOffset) -1 else 1 }.let { -it - 1 }
        return index < sorted.size && sorted[index].intersectsStrict(range)
    }
}

/**
 * Semantic tokens of an injected file whose language has no [LSSemanticTokensProviderBase], e.g. from the injected language's
 * syntax highlighter. Ranges are in [LSInjectedFile.documentWindow] (injected) coordinates and must not overlap.
 */
interface LSInjectedSemanticTokensFallback : LSConfigurationEntry {
    /** Requires read access; checks [job]. */
    fun tokens(injected: LSInjectedFile, job: Job): List<LSSemanticTokenWithRange>
}
