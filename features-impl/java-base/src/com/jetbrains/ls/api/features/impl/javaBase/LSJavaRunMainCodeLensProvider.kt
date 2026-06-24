// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiMethodUtil
import com.jetbrains.ls.api.features.impl.common.codeLens.LSJvmRunMainCodeLensProvider
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.language.LSLanguage

object LSJavaRunMainCodeLensProvider : LSJvmRunMainCodeLensProvider() {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSJavaLanguage)

    override fun findMainEntryPoints(psiFile: PsiFile): List<MainEntryPoint> {
        val javaFile = psiFile as? PsiJavaFile ?: return emptyList()
        return javaFile.classes.mapNotNull { psiClass ->
            val mainMethod = PsiMethodUtil.findMainMethod(psiClass) ?: return@mapNotNull null
            val fqn = psiClass.qualifiedName ?: return@mapNotNull null
            val range = mainMethod.getLspLocationForDefinition()?.range ?: return@mapNotNull null
            MainEntryPoint(range, fqn)
        }
    }
}
