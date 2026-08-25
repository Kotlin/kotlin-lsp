// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.language.LSLanguage

abstract class LSDocumentSymbolProviderPsiBase(supportedLanguage: LSLanguage) : LSDocumentSymbolProviderBase<PsiElement>() {
    override val supportedLanguages: Set<LSLanguage> = setOf(supportedLanguage)
}
