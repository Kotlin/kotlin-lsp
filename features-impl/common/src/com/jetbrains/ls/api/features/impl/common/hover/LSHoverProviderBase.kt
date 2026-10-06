// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.hover

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.hover.LSHoverProvider
import com.jetbrains.ls.api.core.util.getDocumentationTargetsAndOriginAtPosition
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Hover
import com.jetbrains.lsp.protocol.HoverParams
import com.jetbrains.lsp.protocol.MarkupContent
import com.jetbrains.lsp.protocol.MarkupKindType
import com.jetbrains.lsp.protocol.StringOrMarkupContent

abstract class LSHoverProviderBase : LSHoverProvider {
    protected open fun acceptTarget(target: PsiElement): Boolean = true

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getHover(params: HoverParams): Hover? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val (allTargets, originRange, injectedFile) = psiFile.getDocumentationTargetsAndOriginAtPosition(offset)
                val targets = allTargets.filter { psiElement -> acceptTarget(psiElement) }
                if (targets.isEmpty()) return@readAction null

                // the targets of an injection are rendered from the injected file, where they were found
                val from = injectedFile?.psiFile ?: psiFile
                val fromOffset = injectedFile?.documentWindow?.hostToInjected(offset) ?: offset
                val markdown = targets.mapNotNull { psiElement ->
                    generateMarkdownForPsiElementTarget(psiElement, from, fromOffset)
                }.joinToString("\n---\n")
                if (markdown.isEmpty()) return@readAction null

                Hover(
                    contents = Hover.Content.Markup(MarkupContent(MarkupKindType.Markdown, markdown)),
                    range = originRange?.toLspRange(document),
                )
            }
        }
    }

    context(server: LSServer, analysisContext: LSAnalysisContext)
    abstract fun generateMarkdownForPsiElementTarget(target: PsiElement, from: PsiFile, offset: Int): String?

    interface LSMarkdownDocProvider {
        fun getMarkdownDoc(element: PsiElement): String?

        private object Extension : LanguageExtension<LSMarkdownDocProvider>("ls.markdownDocProvider")

        companion object {
            fun getMarkdownDoc(element: PsiElement): String? =
                forLanguage(element.language)?.getMarkdownDoc(element.navigationElement ?: element)

            fun getMarkdownDocAsStringOrMarkupContent(psiElement: PsiElement): StringOrMarkupContent? {
                val doc = getMarkdownDoc(psiElement) ?: return null
                return StringOrMarkupContent(MarkupContent(MarkupKindType.Markdown, doc))
            }

            fun forLanguage(language: Language): LSMarkdownDocProvider? = Extension.forLanguage(language)
        }
    }
}
