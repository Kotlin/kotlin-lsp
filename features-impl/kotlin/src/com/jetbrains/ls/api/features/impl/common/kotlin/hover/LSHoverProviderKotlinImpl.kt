// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.hover

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiPackage
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationArgumentsRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaErrorTypeRenderer
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.renderer.render

object LSHoverProviderKotlinImpl : AbstractLSHoverProvider(TargetKind.ALL) {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)

    context(_: LSServer, _: LSAnalysisContext)
    override fun generateMarkdownForPsiElementTarget(target: PsiElement, from: PsiFile): String? {
        if (from !is KtFile) return null

        return analyze(from) {
            val symbol = when (target) {
                is KtDeclaration -> target.symbol
                is PsiClass -> target.namedClassSymbol
                is PsiMember -> target.callableSymbol
                is PsiPackage -> findPackage(FqName(target.qualifiedName))
                else -> null
            } ?: return null
            getMarkdownContent(symbol)
        }
    }

    context(kaSession: KaSession)
    private fun getMarkdownContent(symbol: KaSymbol): String? = with(kaSession) {
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

    context(_: KaSession)
    private fun getMarkdownDoc(symbol: KaSymbol): String? {
        val psi = symbol.psi ?: return null
        return LSMarkdownDocProvider.getMarkdownDoc(psi)
    }

    private val renderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES.with {
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