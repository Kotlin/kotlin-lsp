// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.apiImpl.textEdits

import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.idea.base.projectStructure.getKaModule
import org.jetbrains.kotlin.psi.KtPsiFactory

internal class KotlinFileForModificationFactory : PsiFileTextEditsCollector.FileForModificationFactory {
    override fun createFileForModifications(file: PsiFile, setOriginalFie: Boolean): PsiFile {
        val project = file.project
        val copyKtFile = KtPsiFactory(project, markGenerated = true, eventSystemEnabled = true).createFile(file.text)
        copyKtFile.contextModule = file.getKaModule(project, useSiteModule = null)
        if (setOriginalFie) {
            copyKtFile.originalFile = file
        }
        return copyKtFile
    }
}