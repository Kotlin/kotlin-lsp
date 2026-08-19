// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.codeInsight.javadoc.JavaDocHighlightingManagerDummyImpl
import com.intellij.codeInsight.javadoc.JavaDocInfoGenerator
import com.intellij.codeInsight.javadoc.JavaDocInfoMarkdownPrinter
import com.intellij.lang.documentation.DocumentationSettings
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase
import java.lang.StringBuilder

internal class LSJavaMarkdownDocProvider : LSHoverProviderBase.LSMarkdownDocProvider {
    override fun getMarkdownDoc(element: PsiElement): String? {

        val printer = object : JavaDocInfoMarkdownPrinter() {
            override fun printLinkURI(builder: StringBuilder, targetElement: PsiElement): StringBuilder {
                return builder.append(LSJavaHoverProvider.makeLinkTo(targetElement))
            }
        }

        val generator = JavaDocInfoGenerator(
            element.project,
            element,
            JavaDocHighlightingManagerDummyImpl.getInstance(),
            false,
            true,
            true,
            DocumentationSettings.InlineCodeHighlightingMode.AS_DEFAULT_CODE,
            false,
            1.0F,
            printer
        )
        return generator.generateDocInfo(null) 
    }
}
