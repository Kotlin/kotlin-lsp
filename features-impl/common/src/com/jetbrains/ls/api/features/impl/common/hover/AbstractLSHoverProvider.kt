// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.hover

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
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
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.hover.LSHoverProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.common.utils.getTargetsAtPosition
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*

abstract class AbstractLSHoverProvider(
    private val targetKinds: Set<TargetKind>
) : LSHoverProvider {
    protected open fun acceptTarget(target: PsiElement): Boolean = true

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun getHover(params: HoverParams): Hover? {
        return withAnalysisContext {
            readAction {
                val virtualFile = params.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val targets = psiFile
                    .getTargetsAtPosition(params.position, document, targetKinds)
                    .filter { psiElement -> acceptTarget(psiElement) }
                if (targets.isEmpty()) return@readAction null

                val markdown = targets.mapNotNull { psiElement ->
                    generateMarkdownForPsiElementTarget(psiElement, psiFile)
                }.joinToString("\n---\n")

                Hover(
                    contents = Hover.Content.Markup(MarkupContent(MarkupKindType.Markdown, markdown)),
                    range = findRange(psiFile, document, params.position),
                )
            }
        }
    }

    private fun findRange(psiFile: PsiFile, document: Document, position: Position): Range? {
        val offset = document.offsetByPosition(position)
        psiFile.findReferenceAt(offset)?.let { return it.element.textRange.toLspRange(document) }
        psiFile.findElementAt(offset)?.let { return it.textRange.toLspRange(document) }
        return null
    }

    context(_: LSServer, _: LSAnalysisContext)
    abstract fun generateMarkdownForPsiElementTarget(target: PsiElement, from: PsiFile): String?

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
