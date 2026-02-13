// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.hover

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiPackage
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.findKDoc
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.renderers.KaAnnotationArgumentsRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaErrorTypeRenderer
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.impl.KDocLink
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.renderer.render

internal object LSKotlinHoverProvider : LSHoverProviderBase(TargetKind.ALL) {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)

    context(server: LSServer, analysisContext: LSAnalysisContext)
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

    context(kaSession: KaSession)
    private fun getMarkdownDoc(symbol: KaSymbol): String? {
        if(symbol is KaDeclarationSymbol && symbol.origin != KaSymbolOrigin.JAVA_LIBRARY && symbol.origin != KaSymbolOrigin.JAVA_SOURCE) {
            @OptIn(KtNonPublicApi::class, KaNonPublicApi::class) // special API for Dokka
            val primaryTag = symbol.findKDoc()?.primaryTag ?: return null
            return primaryTag.getContentWithResolvedAllLinks()
        } else {
            val psi = symbol.psi ?: return null
            return LSMarkdownDocProvider.getMarkdownDoc(psi)
        }
    }

    fun KDocTag.getContentWithResolvedAllLinks(): String {
        val tagContent = getContent()
        val kDocLinks = buildMap {
            this@getContentWithResolvedAllLinks.forEachDescendantOfType<KDocLink> {
                this[it.getLinkText()] = it
            }
        }

        fun resolveToPsi(kDocLink: KDocLink): PsiElement? {
            val lastNameSegment = kDocLink.children.filterIsInstance<KDocName>().lastOrNull()
            return analyze(kDocLink) {
                lastNameSegment?.mainReference?.resolveToSymbols()?.firstOrNull()?.sourcePsiSafe()
            }
        }

        fun resolveKDocLinkToURI(link: String): String? {
            val psi = kDocLinks[link]?.let { resolveToPsi(it) } ?: return null
            val path = psi.containingFile?.virtualFile?.path
            val fileDocument = psi.containingFile?.fileDocument ?: return null
            val startPosition = fileDocument.positionByOffset(psi.textOffset)

            // the format is taken from https://github.com/microsoft/vscode/blob/b3ec8181fc49f5462b5128f38e0723ae85e295c2/src/vs/platform/opener/common/opener.ts#L151-L160
            return "file://$path#${startPosition.line + 1},${startPosition.character + 1}}"
        }

        fun mdLink(kDocLink: String, label: String = kDocLink): String {
            val resolvedLink = resolveKDocLinkToURI(kDocLink) ?: return label
            return  "[$label](${resolvedLink})"
        }

        val replacedKDocLinks = tagContent
            .replace(LABELED_MARKDOWN_LINK) { mdLink(it.groupValues[2], it.groupValues[1]) }
            .replace(MARKDOWN_LINK) { mdLink(it.groupValues[1]) }
        return replacedKDocLinks
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
    private val MARKDOWN_LINK = Regex("\\[(.+?)](?!\\()")
    private val LABELED_MARKDOWN_LINK = Regex("\\[(.+?)]\\[(.+?)]")
}
