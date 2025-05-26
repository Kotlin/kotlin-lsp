// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.completion

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.psi.PsiElement
import com.jetbrains.lsp.protocol.CompletionItemKind

interface LSCompletionItemKindProvider {
    fun getKind(element: PsiElement): CompletionItemKind?

    private object Extension : LanguageExtension<LSCompletionItemKindProvider>("ls.completionItemKindProvider")

    companion object {
        fun getKind(element: PsiElement): CompletionItemKind? =
            forLanguage(element.language)?.getKind(element) ?: forLanguage(Language.ANY)?.getKind(element)

        fun forLanguage(language: Language): LSCompletionItemKindProvider? =
            Extension.forLanguage(language)
    }
}