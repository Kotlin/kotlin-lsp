// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.hover

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase
import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.kdoc.findKDoc
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNonPublicApi

internal class LSKotlinMarkdownDocProvider : LSHoverProviderBase.LSMarkdownDocProvider {
    @OptIn(KtNonPublicApi::class, KaNonPublicApi::class) // special API for Dokka
    override fun getMarkdownDoc(element: PsiElement): String? {
        val ktElement = element.unwrapped as? KtDeclaration ?: return null
        return analyze(ktElement) {
            val kDoc = ktElement.findKDoc() ?: return null
            LSKotlinKDocMarkdown.render(kDoc.primaryTag, kDoc.additionalSections)
        }
    }
}
