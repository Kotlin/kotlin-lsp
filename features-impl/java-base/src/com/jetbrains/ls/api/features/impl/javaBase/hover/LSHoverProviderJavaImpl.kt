// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase.hover

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.javaBase.language.LSJavaLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.URI

open class LSHoverProviderJavaImpl(targetKinds: Set<TargetKind>) : AbstractLSHoverProvider(targetKinds) {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSJavaLanguage)


    context(_: LSServer, _: LSAnalysisContext)
    override fun generateMarkdownForPsiElementTarget(target: PsiElement, from: PsiFile): String? {
        val renderedDeclaration = render(target) ?: return null
        val documentation = LSMarkdownDocProvider.getMarkdownDoc(target)

        return buildString {
            documentation?.let { appendLine(it) }
            append(markdownMultilineCode(renderedDeclaration, language = LSJavaLanguage.lspName))
        }
    }

    private fun render(element: PsiElement): String? {
        return when (element) {
            is PsiMethod -> PsiFormatUtil.formatMethod(element, PsiSubstitutor.EMPTY, OPTIONS, OPTIONS)
            is PsiVariable -> PsiFormatUtil.formatVariable(element, OPTIONS, PsiSubstitutor.EMPTY)
            is PsiClass -> {
                // todo this will not print the class/interface/etc keywords
                PsiFormatUtil.formatClass(element, OPTIONS)
            }
            is PsiPackage -> "package ${element.qualifiedName}"
            else -> null
        }
    }

    companion object {
        private const val OPTIONS: Int =
            PsiFormatUtilBase.SHOW_NAME or
                    PsiFormatUtilBase.SHOW_MODIFIERS or
                    PsiFormatUtilBase.SHOW_TYPE or
                    PsiFormatUtilBase.SHOW_MODIFIERS or
                    PsiFormatUtilBase.SHOW_INITIALIZER or
                    PsiFormatUtilBase.SHOW_PARAMETERS or
                    PsiFormatUtilBase.SHOW_THROWS or
                    PsiFormatUtilBase.SHOW_EXTENDS_IMPLEMENTS    }


}