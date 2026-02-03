// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.lsp.protocol.CompletionItemKind
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal class LSKotlinCompletionItemKindProvider : LSCompletionItemKindProvider {
  override fun getKind(psi: PsiElement): CompletionItemKind? =
    when (val psi = psi.unwrapped) {
      is KtConstructor<*> -> CompletionItemKind.Constructor
      is KtFunction -> when {
        psi.hasModifier(KtTokens.OPERATOR_KEYWORD) -> CompletionItemKind.Operator
        psi.containingClassOrObject != null -> CompletionItemKind.Method
        else -> CompletionItemKind.Function
      }

      is KtProperty -> when {
        psi.hasModifier(KtTokens.CONST_KEYWORD) -> CompletionItemKind.Constant
        psi.isLocal -> CompletionItemKind.Variable
        else -> CompletionItemKind.Property
      }

      is KtParameter -> CompletionItemKind.Variable
      is KtClass -> when {
        psi.isData() -> CompletionItemKind.Struct
        psi.isInterface() -> CompletionItemKind.Interface
        psi.isEnum() -> CompletionItemKind.Enum
        psi is KtEnumEntry -> CompletionItemKind.EnumMember
        else -> CompletionItemKind.Class
      }

      is KtTypeParameter -> CompletionItemKind.TypeParameter
      else -> null
    }
  }