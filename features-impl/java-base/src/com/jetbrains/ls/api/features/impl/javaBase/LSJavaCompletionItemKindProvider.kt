// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiTypeParameter
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.lsp.protocol.CompletionItemKind

internal class LSJavaCompletionItemKindProvider : LSCompletionItemKindProvider {
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

        is PsiKeyword -> CompletionItemKind.Keyword

        else -> null
    }
}
