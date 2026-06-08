// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.rename

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPackage
import com.jetbrains.ls.api.features.impl.common.processors.RefactoringContext
import com.jetbrains.ls.api.features.impl.common.processors.RenameSingleDirectoryContext
import com.jetbrains.ls.api.features.impl.common.processors.findDirectoryInSameSourceRoot
import com.jetbrains.ls.api.features.impl.common.rename.LSRenameProviderBase
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinRenameProvider : LSRenameProviderBase(setOf(LSKotlinLanguage)) {
    override fun getTargetClass(psiFile: PsiFile, name: String): PsiElement? {
        if (psiFile !is KtFile) return null
        return psiFile.classes.firstOrNull { it.name == name }
    }

    override fun createContext(target: PsiElement, newName: String, contextFile: PsiFile): RefactoringContext {
        if (target !is PsiPackage) return super.createContext(target, newName, contextFile)

        val directory = target.findDirectoryInSameSourceRoot(contextFile)
            ?: return super.createContext(target, newName, contextFile)
        return RenameSingleDirectoryContext(directory, newName)
    }
}
