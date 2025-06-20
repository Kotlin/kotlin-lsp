// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.symbols

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.util.parentsOfType
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.impl.common.symbols.AbstractLSWorkspaceSymbolProvider
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoTypeAliasContributor
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal object LSWorkspaceSymbolProviderKotlinImpl : AbstractLSWorkspaceSymbolProvider() {
    override fun getContributors(): List<ChooseByNameContributor> = listOf(
        KotlinGotoClassContributor(),
        KotlinGotoTypeAliasContributor(),
        KotlinGotoFunctionSymbolContributor(),
        KotlinGotoPropertySymbolContributor(),
    )

    context(_: LSServer, _: LSAnalysisContext)
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

}