// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.java.syntax.parser.JavaKeywords
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiSubstitutor.EMPTY
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtil.formatMethod
import com.intellij.psi.util.PsiFormatUtilBase
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.language.LSLanguage

open class LSJavaHoverProvider : LSHoverProviderBase() {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSJavaLanguage)


    context(server: LSServer, analysisContext: LSAnalysisContext)
    override fun generateMarkdownForPsiElementTarget(target: PsiElement, from: PsiFile, offset: Int): String? {
        val renderedDeclaration = render(target) ?: return null
        val documentation = LSMarkdownDocProvider.getMarkdownDoc(target)

        return buildString {
            links(target, from, offset)?.let { appendLine(it) }
            documentation?.let { appendLine(it) }
            append(markdownMultilineCode(renderedDeclaration, language = LSJavaLanguage.lspName))
        }
    }

    private fun links(element: PsiElement, from: PsiFile, offset: Int): String? {
        return when (element) {
            is PsiMethod -> {
                if (!atDeclaration(element, from, offset)) null
                else {
                    val superMethods = element.findSuperMethods()
                    when (superMethods.size) {
                        0 -> null
                        1 -> "[Go to Super Method](${makeLinkTo(superMethods.single()) ?: return null})"
                        else -> "Go to Super Method: " +
                                superMethods.map { m ->
                                    "[${m.containingClass?.name ?: "???"}](${makeLinkTo(m) ?: return null})"
                                }.sorted().joinToString(" | ", "[", "]")
                    }
                }
            }

            else -> null
        }
    }

    private fun atDeclaration(method: PsiMethod, from: PsiFile, offset: Int): Boolean {
        val methodFile = method.containingFile
        if (methodFile != from) return false
        if (!method.textRange.containsOffset(offset)) return false
        val body = method.body
        if (body != null && body.textRange.containsOffset(offset)) return false
        return true
    }

    private fun makeLinkTo(element: PsiMethod): String? {
        val location = element.getLspLocationForDefinition() ?: return null
        return location.uri.uri.uri + "#" + (location.range.start.line + 1) + "," + (location.range.start.character + 1)
    }

    private fun render(element: PsiElement): String? {
        return when (element) {
            is PsiMethod -> formatMethod(element, EMPTY, OPTIONS, OPTIONS)
            is PsiVariable -> PsiFormatUtil.formatVariable(element, OPTIONS, EMPTY)
            is PsiClass -> PsiFormatUtil.formatModifiers(element, PsiFormatUtilBase.SHOW_MODIFIERS)
                .let { if (it.isEmpty()) "" else "$it " } +
                    classKind(element) + ' ' +
                    PsiFormatUtil.formatClass(element,
                        OPTIONS and PsiFormatUtilBase.SHOW_MODIFIERS.inv())
            is PsiPackage -> "package ${element.qualifiedName}"
            else -> null
        }
    }

    private fun classKind(element: PsiClass): @NlsSafe String = when {
        element.isEnum -> JavaKeywords.ENUM
        element.isAnnotationType -> '@' + JavaKeywords.INTERFACE
        element.isInterface -> JavaKeywords.INTERFACE
        element.isRecord -> JavaKeywords.RECORD
        else -> JavaKeywords.CLASS
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
                    PsiFormatUtilBase.SHOW_EXTENDS_IMPLEMENTS
    }


}