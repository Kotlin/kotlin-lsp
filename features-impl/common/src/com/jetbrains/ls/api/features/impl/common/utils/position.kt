// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Location

internal fun TextRange.toLspLocation(file: VirtualFile, document: Document): Location {
    return Location(DocumentUri(file.uri), toLspRange(document))
}

internal fun PsiElement.getLspLocation(): Location? {
    val textRange = textRange ?: return null
    val virtualFile = containingFile.virtualFile
    val document = requireNotNull(virtualFile.findDocument()) { 
        "Got PSI which for which we can't get document: ${virtualFile}" 
    }
    return textRange.toLspLocation(virtualFile, document)
}

fun PsiElement.getLspLocationForDefinition(): Location? {
    val navigationElement = getNavigationElement()
    if (navigationElement != null && navigationElement != this) {
        return navigationElement.getLspLocationForDefinition()
    }
    (this as? PsiNameIdentifierOwner)?.nameIdentifier?.getLspLocation()?.let { return it }
    return getLspLocation()
}