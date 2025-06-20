// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion.rekot

import com.intellij.codeInsight.completion.AllClassesGetter
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.scope.JavaCompletionProcessor
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.scopes.KaTypeScope
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.idea.base.analysis.api.utils.KtSymbolFromIndexProvider
import org.jetbrains.kotlin.idea.completion.KeywordCompletion
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.util.CommentSaver.Companion.tokenType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression

internal object CompletionItemsProvider {
    fun createItems(
        ktFile: KtFile,
        offset: Int,
    ): List<RekotCompletionItem> {
        val element = ktFile.findElementAt(offset - 1) ?: return emptyList()
        val ktElement = element.parentOfType<KtElement>(withSelf = true) ?: return emptyList()

        return analyzeCopy(ktFile, KaDanglingFileResolutionMode.PREFER_SELF) {
            createItems(ktFile, element, ktElement) ?: emptyList()
        }
    }

    context(kaSession: KaSession)
    private fun createItems(ktFile: KtFile, element: PsiElement, parentKtElement: KtElement): List<RekotCompletionItem>?= with(kaSession) {
        val position = getPosition(parentKtElement) ?: run {
            return completeKeywords(element, position = null)
        }
        val filter =
            when (val prefix = position.prefix) {
                null -> { _: Name -> true }
                else -> { name: Name -> matchesPrefix(prefix, name.asString()) }
            }

        val applicabilityChecker =
            when (position) {
                is CompletionPosition.AfterDot ->
                    position.nameExpression?.let { createExtensionCandidateChecker(ktFile, it, position.receiver) } ?: return null

                is CompletionPosition.Identifier ->
                    createExtensionCandidateChecker(
                        ktFile,
                        parentKtElement as KtSimpleNameExpression,
                        explicitReceiver = null,
                    )

                else -> null
            }
        val visibilityChecker =
            when (position) {
                is CompletionPosition.AfterDot ->
                    createUseSiteVisibilityChecker(ktFile.symbol, position.receiver, position.nameExpression ?: position.receiver)

                is CompletionPosition.Identifier -> createUseSiteVisibilityChecker(ktFile.symbol, null, parentKtElement)

                is CompletionPosition.NestedType -> createUseSiteVisibilityChecker(ktFile.symbol, null, parentKtElement)

                is CompletionPosition.Type -> createUseSiteVisibilityChecker(ktFile.symbol, null, parentKtElement)
            }
        val symbolFilter: CompletionItemsCollector.SymbolFilter = when (position) {
            is CompletionPosition.Type if position.isAnnotation -> CompletionItemsCollector.SymbolFilter { symbol ->
                when (symbol) {
                    is KaClassSymbol -> symbol.classKind == KaClassKind.ANNOTATION_CLASS
                    is KaTypeAliasSymbol -> symbol.expandedType.expandedSymbol?.classKind == KaClassKind.ANNOTATION_CLASS
                    else -> false
                }
            }
            else -> CompletionItemsCollector.SymbolFilter { true }
        }
        val collector = CompletionItemsCollector(applicabilityChecker, visibilityChecker, filter, symbolFilter)
        with(collector) {
            when (position) {
                is CompletionPosition.AfterDot -> completeAfterDot(position, ktFile, parentKtElement)
                is CompletionPosition.Identifier -> {
                    collector.add(completeKeywords(parentKtElement, position))
                    completeIdentifier(ktFile, parentKtElement)
                }

                is CompletionPosition.Type -> completeType(ktFile, parentKtElement, filter)
                is CompletionPosition.NestedType -> completeNestedType(position)
            }
        }

        val elements = collector.build().distinctBy { item ->
            when (item) {
                is RekotCompletionItem.Declaration -> item.copy(import = false)
                is RekotCompletionItem.Keyword -> item
            }
        }

        if (elements.isEmpty()) return null
        return elements
    }

    private fun completeKeywords(
        element: PsiElement,
        position: CompletionPosition?,
    ): List<RekotCompletionItem.Keyword> = runCatching {
        val matcher = when {
            position == null -> when {
                element.tokenType == KtTokens.IDENTIFIER -> {
                    element.text.removeSuffix(COMPLETION_FAKE_IDENTIFIER).let(::PlainPrefixMatcher)
                }

                else -> return emptyList()
            }

            else -> position.prefix?.let(::PlainPrefixMatcher) ?: PrefixMatcher.ALWAYS_TRUE
        }
        val result = mutableListOf<RekotCompletionItem.Keyword>()
        KeywordCompletion().complete(
            element,
            matcher,
            isJvmModule = true,
        ) { lookupElement ->
            val presentation = LookupElementPresentation().also { lookupElement.renderElement(it) }
            val text = presentation.itemText ?: lookupElement.lookupString
            result += keywordCompletionItem {
                textToShow = text.trim()
                name = text.trim()
                textToInsert = text
            }
        }
        return result
    }.getOrLogException { LOG.debug(it) }
        ?: emptyList() // do not fail the whole completion when one item fails, not well tested

    context(kaSession: KaSession)
    private fun CompletionItemsCollector.completeAfterDot(
        position: CompletionPosition.AfterDot,
        file: KtFile,
        element: KtElement,
    ) = with(kaSession) {
        when (val receiverTarget = position.receiver.mainReference?.resolveToSymbol()) {
            is KaClassSymbol -> {
                completeStaticMembers(receiverTarget)
                receiverTarget.staticDeclaredMemberScope.classifiers
                    .filterIsInstance<KaClassSymbol>()
                    .firstOrNull { it.classKind == KaClassKind.COMPANION_OBJECT }
                    ?.let { completeScope(it.combinedMemberScope) }
            }

            else -> {
                position.receiver.expressionType?.scope?.let { completeTypeScope(it) }

                if (position.prefix != null) {
                    val scopeContext = file.scopeContext(element)
                    for (scope in scopeContext.scopes) {
                        add(scope.scope.callables(nameFilter).filter { it.isExtension })
                    }
                }

                add(getKotlinDeclarationsFromIndex(file, nameFilter).filter { symbol ->
                    symbol is KaCallableSymbol && symbol.isExtension
                }) { withImport() }
            }
        }
    }

    context(kaSession: KaSession)
    private fun CompletionItemsCollector.completeStaticMembers(kaClassSymbol: KaClassSymbol) = with(kaSession) {
        add(kaClassSymbol.staticDeclaredMemberScope.callables(nameFilter))
    }

    context(_: KaSession)
    private fun CompletionItemsCollector.completeIdentifier(file: KtFile, element: KtElement) {
        completeScopeContext(file, element)
        for (declaration in getKotlinDeclarationsFromIndex(file, nameFilter)) {
            add(declaration) { withImport() }
        }
        for (declaration in getJavaDeclarationsFromIndex(file, nameFilter)) {
            add(declaration) { withImport() }
        }
    }

    context(kaSession: KaSession)
    private fun CompletionItemsCollector.completeScopeContext(file: KtFile, element: KtElement) = with(kaSession) {
        val scopeContext = file.scopeContext(element)
        for (scope in scopeContext.scopes) {
            add(scope.scope.callables(nameFilter))
            add(scope.scope.classifiers(nameFilter))
        }
        for (implicitReceiver in scopeContext.implicitReceivers) {
            implicitReceiver.type.scope?.let { completeTypeScope(it) }
        }
    }

    context(_: KaSession)
    private fun getKotlinDeclarationsFromIndex(ktFile: KtFile, filter: (Name) -> Boolean): Sequence<KaDeclarationSymbol> {
        val symbolProvider = KtSymbolFromIndexProvider(ktFile)
        return (symbolProvider.getKotlinClassesByNameFilter(filter) +
                symbolProvider.getTopLevelCallableSymbolsByNameFilterIncludingExtensions(filter)).take(500)
    }


    context(_: KaSession)
    private fun getJavaDeclarationsFromIndex(ktFile: KtFile, filter: (Name) -> Boolean): Sequence<KaDeclarationSymbol> {
        val symbolProvider = KtSymbolFromIndexProvider(ktFile)

        return symbolProvider.getJavaClassesByNameFilter(filter, psiFilter = psiFilter@{ psiClass ->
            val qualifiedName = psiClass.qualifiedName
            if (qualifiedName == null) return@psiFilter false
            if (javaInternalPackages.any { qualifiedName.startsWith(it) }) return@psiFilter false
            if (PsiReferenceExpressionImpl.seemsScrambled(psiClass) || JavaCompletionProcessor.seemsInternal(psiClass)) {
                return@psiFilter false
            }
            AllClassesGetter.isAcceptableInContext(ktFile, psiClass, false, false)
        }).take(500)

    }


    context(kaSession: KaSession)
    private fun CompletionItemsCollector.completeType(file: KtFile, element: KtElement, nameFilter: (Name) -> Boolean) = with(kaSession) {
        for (scope in file.scopeContext(element).scopes) {
            add(scope.scope.classifiers(nameFilter))
        }

        val symbolProvider = KtSymbolFromIndexProvider(file)

        for (declaration in symbolProvider.getKotlinClassesByNameFilter(nameFilter)) {
            add(declaration) { withImport() }
        }

        for (declaration in symbolProvider.getJavaClassesByNameFilter(nameFilter)) {
            add(declaration) { withImport() }
        }
    }

    context(kaSession: KaSession)
    private fun CompletionItemsCollector.completeNestedType(position: CompletionPosition.NestedType) = with(kaSession) {
        val classifiers =
            when (val symbol = position.receiver.referenceExpression?.mainReference?.resolveToSymbol()) {
                is KaTypeAliasSymbol -> symbol.expandedType.scope?.getClassifierSymbols(nameFilter)
                is KaDeclarationContainerSymbol -> symbol.combinedMemberScope.classifiers(nameFilter)
                else -> null
            } ?: return
        add(classifiers)
    }

    context(_: KaSession)
    private fun CompletionItemsCollector.completeTypeScope(scope: KaTypeScope) {
        add(scope.getCallableSignatures(nameFilter))
        add(scope.getClassifierSymbols(nameFilter))
    }

    context(_: KaSession)
    private fun CompletionItemsCollector.completeScope(scope: KaScope) {
        add(scope.callables(nameFilter))
        add(scope.classifiers(nameFilter))
    }

    context(_: KaSession)
    private fun getPosition(element: KtElement): CompletionPosition? =
        when (element) {
            is KtNameReferenceExpression -> {
                when (val parent = element.parent) {
                    is KtDotQualifiedExpression if parent.selectorExpression == element -> getPosition(parent)
                    is KtUserType if parent.referenceExpression == element -> getPositionByUserType(parent)
                    else -> CompletionPosition.Identifier(
                        element.getReferencedName().removeSuffix(COMPLETION_FAKE_IDENTIFIER)
                    )
                }
            }

            is KtQualifiedExpression -> {
                val selector = element.selectorExpression
                CompletionPosition.AfterDot(
                    (selector as? KtNameReferenceExpression)
                        ?.getReferencedName()
                        ?.removeSuffix(COMPLETION_FAKE_IDENTIFIER),
                    element.receiverExpression,
                )
            }

            is KtUserType -> getPositionByUserType(element)

            else -> null
        }

    private fun getPositionByUserType(element: KtUserType) =
        when (val qualifier = element.qualifier) {
            null -> CompletionPosition.Type(element.referencedName.orEmpty().removeSuffix(COMPLETION_FAKE_IDENTIFIER), element.isAnnotation())
            else ->
                CompletionPosition.NestedType(
                    element.referencedName.orEmpty().removeSuffix(COMPLETION_FAKE_IDENTIFIER),
                    qualifier,
                )
        }

    fun KtUserType.isAnnotation(): Boolean {
        val typeReference = parent as? KtTypeReference ?: return false
        val constructor = typeReference.parent as? KtConstructorCalleeExpression ?: return false
        return constructor.parent is KtAnnotationEntry
    }

    private sealed interface CompletionPosition {
        val prefix: String?

        class Identifier(override val prefix: String) : CompletionPosition

        class AfterDot(override val prefix: String?, val receiver: KtExpression) : CompletionPosition {
            val nameExpression: KtSimpleNameExpression?
                get() = when (val selector = (receiver.parent as KtQualifiedExpression).selectorExpression) {
                    is KtSimpleNameExpression -> selector
                    is KtCallExpression -> selector.getCallNameExpression()
                    else -> null
                }
        }

        class Type(override val prefix: String, val isAnnotation: Boolean) : CompletionPosition

        class NestedType(override val prefix: String?, val receiver: KtUserType) : CompletionPosition
    }

    private val javaInternalPackages = listOf(
        "sun.", "com.sun.",
        "apple.", "com.apple.", "com.microsoft.",
        "org.jcp.xml.dsig.internal.",
        "jdk.internal.", "jdk.javadoc.internal.", "jdk.jfr.internal.", "jdk.tools.jlink.internal.", "jdk.xml.internal.", "jdk.jpackage.internal.",
    )

}

private val LOG = fileLogger()