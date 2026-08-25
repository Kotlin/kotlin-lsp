package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizerPsiBase
import com.jetbrains.lsp.protocol.SymbolKind
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

class LSKotlinDocumentSymbolCustomizer : LSDocumentSymbolCustomizerPsiBase() {

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