// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.hover

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiPackage
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.javaInterop.callableSymbol
import org.jetbrains.kotlin.analysis.api.javaInterop.namedClassSymbol
import org.jetbrains.kotlin.analysis.api.kdoc.findKDoc
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationArgumentsRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.declarations.renderers.classifiers.KaSingleTypeParameterSymbolRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaErrorTypeRenderer
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulSymbol
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.containingModule
import org.jetbrains.kotlin.analysis.api.symbols.findPackage
import org.jetbrains.kotlin.analysis.api.symbols.pointers.restoreSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.render
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNonPublicApi

internal object LSKotlinHoverProvider : LSHoverProviderBase() {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)

    context(server: LSServer, analysisContext: LSAnalysisContext)
    override fun generateMarkdownForPsiElementTarget(target: PsiElement, from: PsiFile, offset: Int): String? {
        if (from !is KtFile) return null

        return analyze(from) {
            val symbol = when (target) {
                is KtDeclaration -> target.symbol
                is PsiClass -> target.namedClassSymbol
                is PsiMember -> target.callableSymbol
                is PsiPackage -> findPackage(FqName(target.qualifiedName))
                is KtCallElement -> target.resolveSuccessfulSymbol()
                else -> null
            } ?: return null

            val symbolPointer = symbol.createPointer()

            /**
             * We have to analyze `symbol` in its own `containingModule`'s session
             * due to a bug in `getExpectsForActual` (see KT-89021)
             */
            analyze(symbol.containingModule) {
                val restoredSymbol = symbolPointer.restoreSymbol() ?: return null

                getMarkdownContent(restoredSymbol)
            }
        }
    }

    context(session: KaSession)
    private fun getMarkdownContent(symbol: KaSymbol): String? = with(session) {
        val renderedSymbol = when (symbol) {
            is KaPackageSymbol -> "package ${symbol.fqName.render()}"
            is KaDeclarationSymbol -> buildString {
                if (symbol is KaConstructorSymbol) {
                    symbol.containingDeclaration?.let { classSymbol ->
                        appendLine(classSymbol.render(renderer))
                    }
                }
                append(symbol.render(renderer))
            }

            else -> null
        } ?: return null

        val documentation = getMarkdownDoc(symbol)

        return buildString {
            documentation?.let { appendLine(it) }
            append(markdownMultilineCode(renderedSymbol, language = LSKotlinLanguage.lspName))
        }
    }

    @OptIn(KtNonPublicApi::class, KaNonPublicApi::class) // special API for Dokka
    context(kaSession: KaSession)
    private fun getMarkdownDoc(symbol: KaSymbol): String? {
        if(symbol is KaDeclarationSymbol && symbol.origin != KaSymbolOrigin.JAVA_LIBRARY && symbol.origin != KaSymbolOrigin.JAVA_SOURCE) {
            val kDoc = symbol.findKDoc() ?: return null
            return LSKotlinKDocMarkdown.render(kDoc.primaryTag, kDoc.additionalSections)
        } else {
            val psi = symbol.psi ?: return null
            return LSMarkdownDocProvider.getMarkdownDoc(psi)
        }
    }

    private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES.with {
        singleTypeParameterRenderer =
            KaSingleTypeParameterSymbolRenderer.WITH_COMMA_SEPARATED_BOUNDS

        typeRenderer = typeRenderer.with {
            errorTypeRenderer = KaErrorTypeRenderer.AS_CODE_IF_POSSIBLE
        }

        annotationRenderer = annotationRenderer.with {
            val originalFilter = annotationFilter
            annotationFilter =
                originalFilter and KaRendererAnnotationsFilter { annotation, _ ->
                    // to avoid weird code on unresolved annotations caused by a bug in Kotlin Analysis API (KT-76373)
                    annotation.classId?.shortClassName?.asString() != "<error>"
                }

            annotationArgumentsRenderer = KaAnnotationArgumentsRenderer.NONE
        }
    }
}
