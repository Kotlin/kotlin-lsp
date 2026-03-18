// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.psi.PsiElement
import com.jetbrains.lsp.protocol.CompletionItemKind

interface LSCompletionItemKindProvider {
    fun getKind(completionItem: LSCompletionCandidate): CompletionItemKind?

    private object Extension : LanguageExtension<LSCompletionItemKindProvider>("ls.completionItemKindProvider")

    companion object {
        fun getKind(completionItem: LSCompletionCandidate): CompletionItemKind? =
            forLanguage(completionItem.language)?.getKind(completionItem) ?: forLanguage(Language.ANY)?.getKind(completionItem)

        fun getKind(element: PsiElement): CompletionItemKind? {
            val completionItem = PsiElementWrappingLSCompletionCandidate(element)
            return forLanguage(completionItem.language)?.getKind(completionItem)
                ?: forLanguage(Language.ANY)?.getKind(completionItem)
        }

        fun forLanguage(language: Language): LSCompletionItemKindProvider? =
            Extension.forLanguage(language)
    }
}

interface LSCompletionCandidate {
    val language: Language
    val result: Any
}

private data class PsiElementWrappingLSCompletionCandidate(val psiElement: PsiElement) : LSCompletionCandidate {
    override val language: Language = psiElement.language
    override val result: Any = psiElement
}
