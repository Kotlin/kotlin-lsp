// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.hover

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.hover.LSHoverProvider
import com.jetbrains.lsp.protocol.Hover
import com.jetbrains.lsp.protocol.HoverParams
import com.jetbrains.lsp.protocol.MarkupContent
import com.jetbrains.lsp.protocol.MarkupKindType
import com.jetbrains.lsp.protocol.StringOrMarkupContent

abstract class AbstractLSHoverProvider : LSHoverProvider {
    context(_: LSServer)
    override suspend fun getHover(params: HoverParams): Hover? {
        return withAnalysisContext {
            runReadAction a@{
                val file = params.findVirtualFile() ?: return@a null
                val psiFile = file.findPsiFile(project) ?: return@a null
                val document = file.findDocument() ?: return@a null
                val offset = document.offsetByPosition(params.position)
                val reference = psiFile.findReferenceAt(offset) ?: return@a null

                val markdown = generateMarkdownForElementReferencedBy(file, reference) ?: return@a null

                Hover(
                    Hover.Content.Markup(MarkupContent(MarkupKindType.Markdown, markdown)),
                    range = reference.element.textRange.toLspRange(document),
                )
            }
        }
    }

    context(_: LSServer, _: LSAnalysisContext)
    abstract fun generateMarkdownForElementReferencedBy(virtualFile: VirtualFile, reference: PsiReference): String?

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

            fun forLanguage(language: Language): LSMarkdownDocProvider? =
                Extension.forLanguage(language)
        }
    }
}