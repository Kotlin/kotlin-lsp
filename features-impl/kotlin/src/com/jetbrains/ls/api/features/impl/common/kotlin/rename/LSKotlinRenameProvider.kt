// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.rename

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.rename.LSRenameProviderBase
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinRenameProvider : LSRenameProviderBase(setOf(LSKotlinLanguage)) {
    override fun getTargetClass(psiFile: PsiFile): PsiElement? {
        if (psiFile !is KtFile) return null
        return psiFile.classes.singleOrNull()
    }

}
