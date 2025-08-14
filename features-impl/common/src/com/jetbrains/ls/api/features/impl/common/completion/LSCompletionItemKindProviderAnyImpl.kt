// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPackage
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.lsp.protocol.CompletionItemKind

internal class LSCompletionItemKindProviderAnyImpl: LSCompletionItemKindProvider {
    override fun getKind(element: PsiElement): CompletionItemKind? = when (element) {
        is PsiFile -> CompletionItemKind.File
        is PsiPackage -> CompletionItemKind.Module
        else -> null
    }
}