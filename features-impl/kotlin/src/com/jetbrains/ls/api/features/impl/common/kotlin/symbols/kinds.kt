// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.symbols

import com.intellij.psi.PsiElement
import com.jetbrains.lsp.protocol.SymbolKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal fun PsiElement.getKind(): SymbolKind? = when (this) {
    is KtFile -> SymbolKind.File
    is KtEnumEntry -> SymbolKind.EnumMember
    is KtClass -> when {
        isInterface() -> SymbolKind.Interface
        isEnum() -> SymbolKind.Enum
        isData() -> SymbolKind.Struct
        else -> SymbolKind.Class
    }

    is KtObjectDeclaration -> SymbolKind.Object
    is KtConstructor<*> -> SymbolKind.Constructor
    is KtClassInitializer -> SymbolKind.Constructor
    is KtNamedFunction -> when {
        containingClassOrObject != null -> SymbolKind.Method
        hasModifier(KtTokens.OPERATOR_KEYWORD) -> SymbolKind.Operator
        else -> SymbolKind.Function
    }

    is KtProperty -> when {
        isLocal -> SymbolKind.Variable
        hasModifier(KtTokens.CONST_KEYWORD) -> SymbolKind.Constant
        else -> SymbolKind.Property
    }
    is KtPropertyAccessor -> SymbolKind.Method

    is KtTypeAlias -> SymbolKind.Class

    is KtParameter -> SymbolKind.Variable
    is KtTypeParameter -> SymbolKind.TypeParameter
    else -> null
}