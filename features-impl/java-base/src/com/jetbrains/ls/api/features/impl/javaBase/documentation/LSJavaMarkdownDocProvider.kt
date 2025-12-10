// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaDocumentedElement
import com.jetbrains.ls.api.features.impl.common.documentaiton.formatDocComment
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider
import com.jetbrains.ls.api.features.impl.common.hover.markdownMultilineCode
import com.jetbrains.ls.api.features.impl.javaBase.language.LSJavaLanguage

internal class LSJavaMarkdownDocProvider : AbstractLSHoverProvider.LSMarkdownDocProvider {
    override fun getMarkdownDoc(element: PsiElement): String? {
        if (element !is PsiJavaDocumentedElement) return null
        val javadocText = element.docComment?.text ?: return null

        // TODO LSP-239 should be rendered as proper markdown
        return markdownMultilineCode(
            code = formatDocComment(javadocText),
            language = LSJavaLanguage.lspName,
        )
    }
}
