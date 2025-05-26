// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.hover

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.psi.KtDeclaration

internal class LSMarkdownDocProviderKotlinImpl : AbstractLSHoverProvider.LSMarkdownDocProvider {
    override fun getMarkdownDoc(element: PsiElement): String? {
        val ktElement = element.unwrapped as? KtDeclaration ?: return null
        val kdDocText = ktElement.docComment?.text ?: return null
        return markdownMultilineCode(
            kdDocText.formatKdoc(),
            language = LSKotlinLanguage.lspName
        )
    }

    private fun String.formatKdoc(): String {
        val trimmedLines = trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return trimmedLines.joinToString(separator = "\n") { if (it.startsWith("/*")) it else " $it" }
    }
}