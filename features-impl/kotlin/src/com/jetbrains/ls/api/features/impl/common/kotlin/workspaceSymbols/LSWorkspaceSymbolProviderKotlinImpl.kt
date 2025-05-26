// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.workspaceSymbols

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.util.parentsOfType
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.impl.common.workspaceSymbols.AbstractLSWorkspaceSymbolProvider
import com.jetbrains.lsp.protocol.SymbolKind
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoTypeAliasContributor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal object LSWorkspaceSymbolProviderKotlinImpl : AbstractLSWorkspaceSymbolProvider() {
    override fun getContributors(): List<ChooseByNameContributor> = listOf(
        KotlinGotoClassContributor(),
        KotlinGotoTypeAliasContributor(),
        KotlinGotoFunctionSymbolContributor(),
        KotlinGotoPropertySymbolContributor(),
    )

    context(LSServer, LSAnalysisContext)
    override fun createWorkspaceSymbol(
        item: NavigationItem,
        contributor: ChooseByNameContributor
    ): WorkspaceSymbol? {
        val ktNamedDeclaration = when (item) {
            is PsiElementNavigationItem -> item.targetElement as? KtNamedDeclaration
            is KtNamedDeclaration -> item
            else -> null
        } ?: return null
        return WorkspaceSymbol(
            item.name ?: return null,
            kind = ktNamedDeclaration.getKind() ?: return null,
            tags = null, // todo handle deprecations
            containerName = ktNamedDeclaration.getClosestContainerQualifiedName(),
            location = ktNamedDeclaration.getLspLocationForDefinition() ?: return null,
            data = null,
        )
    }

    private fun KtNamedDeclaration.getClosestContainerQualifiedName(): String {
        return parentsOfType<KtNamedDeclaration>(withSelf = false)
            .firstNotNullOfOrNull { it.fqName?.asString() }
            ?: containingKtFile.packageFqName.asString()
    }

    private fun KtNamedDeclaration.getKind(): SymbolKind? = when (this) {
        is KtEnumEntry -> SymbolKind.EnumMember
        is KtClass -> when {
            isInterface() -> SymbolKind.Interface
            isEnum() -> SymbolKind.Enum
            else -> SymbolKind.Class
        }

        is KtObjectDeclaration -> SymbolKind.Object
        is KtConstructor<*> -> SymbolKind.Constructor
        is KtNamedFunction -> when {
            containingClassOrObject != null -> SymbolKind.Method
            hasModifier(KtTokens.OPERATOR_KEYWORD) -> SymbolKind.Operator
            else -> SymbolKind.Function
        }

        is KtProperty -> when {
            isLocal -> SymbolKind.Variable
            hasModifier(KtTokens.CONST_KEYWORD) -> SymbolKind.Constant
            else -> SymbolKind.Property
        }

        is KtTypeAlias -> SymbolKind.Class

        is KtParameter -> SymbolKind.Variable
        is KtTypeParameter -> SymbolKind.TypeParameter
        else -> null
    }
}