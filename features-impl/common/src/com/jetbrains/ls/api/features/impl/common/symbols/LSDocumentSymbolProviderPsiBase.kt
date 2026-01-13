// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocation
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.language.LSLanguage

abstract class LSDocumentSymbolProviderPsiBase(supportedLanguage: LSLanguage) : LSDocumentSymbolProviderBase<PsiElement>() {
    override val supportedLanguages: Set<LSLanguage> = setOf(supportedLanguage)

    override fun getRanges(element: PsiElement): ElementRanges? {
        val range = element.getLspLocation()?.range ?: return null
        val selectionRange = element.getLspLocationForDefinition()?.range ?: return null
        return ElementRanges(range, selectionRange)
    }

    override fun getName(element: PsiElement): String? =
        when (element) {
            is PsiNameIdentifierOwner -> element.name
            else -> null
        }

    override fun getRootDeclarations(psiFile: PsiFile): List<PsiElement> {
        return getNestedDeclarations(psiFile)
    }

}
