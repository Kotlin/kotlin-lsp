// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.symbols

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.symbols.LSDocumentSymbolProviderBase
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.SymbolKind
import org.jetbrains.kotlin.psi.*

internal object LSKotlinDocumentSymbolProvider: LSDocumentSymbolProviderBase() {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    override fun getName(element: PsiElement): String? =
        when (element) {
            is KtClassInitializer -> "<class initializer>"
            is KtPropertyAccessor ->
                when {
                    element.isGetter -> "get"
                    else -> "set"
                }
            else -> super.getName(element)
        }


    override fun getKind(element: PsiElement): SymbolKind? =
       element.getKind()

    override fun isDeprecated(element: PsiElement): Boolean =
        // TODO: Something more robust
        (element as? KtNamedDeclaration)?.annotationEntries?.any { it.shortName.toString() == "Deprecated" } == true

    override fun getNestedDeclarations(element: PsiElement): List<PsiElement> =
        when (element) {
            is KtClassOrObject ->
                element.primaryConstructorParameters.filter { it.valOrVarKeyword != null } +
                    listOfNotNull(element.primaryConstructor) +
                    element.declarations
            is KtDeclarationContainer -> element.declarations
            is KtProperty -> element.accessors
            else -> emptyList()
        }
}