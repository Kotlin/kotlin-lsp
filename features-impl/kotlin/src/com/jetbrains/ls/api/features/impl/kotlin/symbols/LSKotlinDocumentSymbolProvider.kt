// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.symbols

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizer
import com.jetbrains.ls.api.features.impl.common.symbols.LSDocumentSymbolProviderPsiBase
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.psi.LSKotlinDocumentSymbolCustomizer

internal object LSKotlinDocumentSymbolProvider: LSDocumentSymbolProviderPsiBase(LSKotlinLanguage) {
    override fun createCustomizer(): LSDocumentSymbolCustomizer<PsiElement> {
        return LSKotlinDocumentSymbolCustomizer()
    }
}
