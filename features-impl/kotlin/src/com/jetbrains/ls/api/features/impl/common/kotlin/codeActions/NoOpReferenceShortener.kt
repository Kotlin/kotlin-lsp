package com.jetbrains.ls.api.features.impl.common.kotlin.codeActions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.components.ShortenOptions
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

internal class NoOpReferenceShortener : ShortenReferencesFacility {
    override fun shorten(file: KtFile, range: TextRange, shortenOptions: ShortenOptions) {}

    override fun shorten(element: KtElement, shortenOptions: ShortenOptions): PsiElement? {
        return element
    }
}
