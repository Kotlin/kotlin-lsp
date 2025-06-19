// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.utils

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.descendantsOfType
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.lsp.protocol.Range

/**
 * Retrieves all non-whitespace child elements from the given `PsiFile`.
 * Optionally, filters these elements based on the provided range.
 *
 * @param range an optional range that defines a subset of the document; only elements within this range are included
 * @return a list of non-whitespace child elements, filtered by the specified range if provided
 */
fun PsiFile.allNonWhitespaceChildren(
    document: Document,
    range: Range?,
): List<PsiElement> {
    val all = descendantsOfType<PsiElement>().filter { it !is PsiWhiteSpace }
    if (range == null) return all.toList()
    val textRange = range.toTextRange(document)
    return all.filter { textRange.intersects(it.textRange) }.toList()
}