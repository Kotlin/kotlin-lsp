// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.apiImpl.textEdits

import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.idea.base.projectStructure.getKaModule
import org.jetbrains.kotlin.psi.KtPsiFactory

internal class KotlinFileForModificationFactory : PsiFileTextEditsCollector.FileForModificationFactory {
    @OptIn(KaImplementationDetail::class)
    override fun createFileForModifications(file: PsiFile): PsiFile =
        KtPsiFactory(project = file.project, markGenerated = false, eventSystemEnabled = true)
            .createFile(file.text)
            .also {
                val module = file.getKaModule(file.project, useSiteModule = null)
                it.contextModule = module
                it.name = file.name
            }
}
