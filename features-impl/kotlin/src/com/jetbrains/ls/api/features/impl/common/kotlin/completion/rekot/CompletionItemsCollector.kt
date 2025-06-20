// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion.rekot

import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.diagnostic.getOrLogException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaCompletionExtensionCandidateChecker
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.components.KaUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDeclaration
import kotlin.contracts.contract

internal class CompletionItemsCollector(
    private val applicabilityChecker: KaCompletionExtensionCandidateChecker?,
    private val visibilityChecker: KaUseSiteVisibilityChecker,
    val nameFilter: (Name) -> Boolean,
    val symbolFilter:  SymbolFilter,
) {
    fun interface SymbolFilter {
        context(_: KaSession) fun accepts(symbol: KaDeclarationSymbol): Boolean
    }
    private val factory: CompletionItemFactory = CompletionItemFactory

    private val items = mutableListOf<RekotCompletionItem>()
    private val symbols = mutableSetOf<KaDeclarationSymbol>()

    context(kaSession: KaSession)
    fun add(declaration: KtDeclaration?, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        with(kaSession) { add(declaration?.symbol, modify) }
    }

    context(_: KaSession)
    @JvmName("addDeclarations")
    fun add(declarations: Iterable<KtDeclaration>, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        declarations.forEach { add(it, modify) }
    }

    context(_: KaSession)
    fun add(symbol: KaDeclarationSymbol?, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        when (symbol) {
            null -> {}
            in symbols -> {}

            is KaCallableSymbol -> {
                val substituted = symbol.asApplicableSignature()
                if (substituted != null) {
                    add(substituted, modify)
                } else if (!symbol.isExtension) {
                    _add(symbol, modify)
                }
            }

            else -> {
                _add(symbol, modify)
            }
        }
    }

    context(_: KaSession)
    @JvmName("addSymbols")
    fun add(symbols: Sequence<KaDeclarationSymbol>, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        symbols.forEach { add(it, modify) }
    }

    context(_: KaSession)
    fun add(signature: KaCallableSignature<*>?, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        _add(signature, modify)
    }

    context(_: KaSession)
    @JvmName("addSignatures")
    fun add(symbols: Sequence<KaCallableSignature<*>>, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        symbols.forEach { add(it, modify) }
    }

    fun add(items: List<RekotCompletionItem>) {
        this.items += items
    }

    context(_: KaSession)
    @Suppress("FunctionName")
    private fun _add(symbol: KaDeclarationSymbol?, modify: (DeclarationCompletionItemBuilder.() -> Unit)?) {
        if (!acceptsSymbol(symbol)) return
        val item = factory.createCompletionItem(symbol) ?: return
        symbols += symbol
        items +=
            if (modify == null) item
            else {
                declarationCompletionItem {
                    with(item)
                    modify()
                }
            }
    }

    context(_: KaSession)
    @Suppress("FunctionName")
    private fun _add(signature: KaCallableSignature<*>?, modify: (DeclarationCompletionItemBuilder.() -> Unit)?) {
        if (!acceptsSymbol(signature?.symbol)) return

        val item = factory.createCompletionItem(signature) ?: return
        symbols += signature.symbol
        items +=
            if (modify == null) item
            else
                declarationCompletionItem {
                    with(item)
                    modify()
                }
    }

    context(_: KaSession)
    private fun acceptsSymbol(symbol: KaDeclarationSymbol?): Boolean {
        contract { returns(true) implies (symbol != null) }
        return runCatching {
            if (symbol == null) return false
            if (symbol in symbols) return false
            if (symbol.origin !in setOf(KaSymbolOrigin.JAVA_LIBRARY)) {
                if (!visibilityChecker.isVisible(symbol)) {
                    return false
                }
            }
            if (symbol.name?.asString()?.contains(COMPLETION_FAKE_IDENTIFIER) == true) return false
            if (!symbolFilter.accepts(symbol)) return false
            return true
        }.getOrLogException { LOG.debug(it) } ?: false
    }

    context(kaSession: KaSession)
    private fun KaCallableSymbol.asApplicableSignature(): KaCallableSignature<KaCallableSymbol>? = with(kaSession) {
        val checker = applicabilityChecker ?: return asSignature()
        return runCatching {
            when (val applicability = checker.computeApplicability(this@asApplicableSignature)) {
                is KaExtensionApplicabilityResult.Applicable -> substitute(applicability.substitutor)
                else -> null
            }
        }.getOrLogException { LOG.debug(it) }
    }

    fun build(): Collection<RekotCompletionItem> {
        return items
    }
}

private val LOG = fileLogger()
