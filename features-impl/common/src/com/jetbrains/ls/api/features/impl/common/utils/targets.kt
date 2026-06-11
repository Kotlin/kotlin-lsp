// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.lang.documentation.impl.documentationTargets
import com.intellij.model.psi.PsiSymbolService
import com.intellij.model.psi.impl.targetDeclarationAndReferenceSymbols
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.lsp.protocol.Position

fun PsiFile.getTargetsAtPosition(position: Position, document: Document, targetKinds: Set<TargetKind>): List<PsiElement> {
    val offset = document.offsetByPosition(position)
    val psiSymbolService = PsiSymbolService.getInstance()
    val (declared, referenced) = targetDeclarationAndReferenceSymbols(this, offset)
    when {
        TargetKind.REFERENCE in targetKinds && referenced.isNotEmpty() -> {
            return referenced.mapNotNull { psiSymbolService.extractElementFromSymbol(it) }
        }

        TargetKind.DECLARATION in targetKinds && declared.isNotEmpty() -> {
            return declared.mapNotNull { psiSymbolService.extractElementFromSymbol(it) }
        }

        else -> return emptyList()
    }
}

fun PsiFile.getDocumentationTargetAtPosition(offset: Int): List<PsiElement> {
    val targets = documentationTargets(this, offset, false)
    return targets.mapNotNull { it.navigatable as? PsiElement }
}

enum class TargetKind {
    DECLARATION,
    REFERENCE,

    ;

    companion object {
        val ALL: Set<TargetKind> = entries.toSet()
    }
}