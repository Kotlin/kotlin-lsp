// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase.completion

import com.intellij.psi.*
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.lsp.protocol.CompletionItemKind

internal class LSCompletionItemKindProviderJavaImpl : LSCompletionItemKindProvider {
    override fun getKind(element: PsiElement): CompletionItemKind? = when (element) {
        is PsiEnumConstant -> CompletionItemKind.EnumMember
        is PsiMethod -> when {
            element.isConstructor -> CompletionItemKind.Constructor
            else -> CompletionItemKind.Method
        }
        is PsiParameter -> CompletionItemKind.Variable
        is PsiLocalVariable -> CompletionItemKind.Variable
        is PsiPackage -> CompletionItemKind.Module
        is PsiField -> CompletionItemKind.Field

        is PsiTypeParameter -> CompletionItemKind.TypeParameter
        is PsiClass -> when {
            element.isInterface -> CompletionItemKind.Interface
            element.isEnum -> CompletionItemKind.Enum
            else -> CompletionItemKind.Class
        }
        is PsiPrimitiveType -> CompletionItemKind.Keyword
        is PsiKeyword -> CompletionItemKind.Keyword

        else -> null
    }
}