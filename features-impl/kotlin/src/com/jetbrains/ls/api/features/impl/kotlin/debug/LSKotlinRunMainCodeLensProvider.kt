// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.debug

import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.features.impl.common.codeLens.LSJvmRunMainCodeLensProvider
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinMainFunctionDetector
import org.jetbrains.kotlin.idea.base.codeInsight.findMain
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

object LSKotlinRunMainCodeLensProvider : LSJvmRunMainCodeLensProvider() {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    override fun findMainEntryPoints(psiFile: PsiFile): List<MainEntryPoint> {
        val ktFile = psiFile as? KtFile ?: return emptyList()
        val detector = KotlinMainFunctionDetector.getInstance()
        return buildList {
            // Top-level `fun main`, launched via the file facade class (e.g. `MainKt`).
            addMainEntryPoint(detector.findMain(ktFile), ktFile)
            // `main` in an `object`, or a `@JvmStatic main` in a `companion object`, launched via that class.
            for (classOrObject in ktFile.declarations.filterIsInstance<KtClassOrObject>()) {
                addMainEntryPoint(detector.findMain(classOrObject), classOrObject)
            }
        }
    }

    private fun MutableList<MainEntryPoint>.addMainEntryPoint(mainFunction: KtNamedFunction?, owner: KtDeclarationContainer) {
        if (mainFunction == null) return
        val mainClass = owner.mainClassFqName() ?: return
        val range = mainFunction.getLspLocationForDefinition()?.range ?: return
        add(MainEntryPoint(range, mainClass))
    }

    private fun KtDeclarationContainer.mainClassFqName(): String? = when (this) {
        is KtFile -> javaFileFacadeFqName.asString()
        is KtClassOrObject -> fqName?.asString()
        else -> null
    }
}
