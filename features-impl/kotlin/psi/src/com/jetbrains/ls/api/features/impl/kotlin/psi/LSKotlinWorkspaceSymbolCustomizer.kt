package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.util.parentsOfType
import com.jetbrains.ls.api.core.features.LSWorkspaceSymbolCustomizer
import com.jetbrains.ls.api.core.features.onlyTypeSymbolKinds
import com.jetbrains.ls.api.core.util.getWorkspaceSymbolLocation
import com.jetbrains.lsp.protocol.SymbolKind
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor
import org.jetbrains.kotlin.idea.goto.KotlinGotoTypeAliasContributor
import org.jetbrains.kotlin.psi.KtNamedDeclaration

class LSKotlinWorkspaceSymbolCustomizer : LSWorkspaceSymbolCustomizer {
    override fun getContributors(): List<ChooseByNameContributor> = listOf(
        KotlinGotoClassSymbolContributor(),
        KotlinGotoTypeAliasContributor(),
        KotlinGotoFunctionSymbolContributor(),
        KotlinGotoPropertySymbolContributor(),
    )

    // A type alias has the Class kind, see `getKind`.
    override fun getContributors(kinds: Set<SymbolKind>?): List<ChooseByNameContributor> =
        if (onlyTypeSymbolKinds(kinds)) listOf(KotlinGotoClassSymbolContributor(), KotlinGotoTypeAliasContributor()) else getContributors()

    override fun createWorkspaceSymbol(
        item: NavigationItem,
        contributor: ChooseByNameContributor,
        qualifiedQuery: Boolean,
        lazyLocation: Boolean,
    ): WorkspaceSymbol? {
        val ktNamedDeclaration = when (item) {
            is PsiElementNavigationItem -> item.targetElement as? KtNamedDeclaration
            is KtNamedDeclaration -> item
            else -> null
        } ?: return null
        return WorkspaceSymbol(
            (if (qualifiedQuery) ktNamedDeclaration.fqName?.asString() else null) ?: item.name ?: return null,
            kind = ktNamedDeclaration.getKind() ?: return null,
            tags = null, // TODO: Handle deprecated declarations.
            containerName = ktNamedDeclaration.getClosestContainerQualifiedName(),
            location = ktNamedDeclaration.getWorkspaceSymbolLocation(lazyLocation) ?: return null,
            data = null,
        )
    }

    private fun KtNamedDeclaration.getClosestContainerQualifiedName(): String {
        return parentsOfType<KtNamedDeclaration>(withSelf = false)
            .firstNotNullOfOrNull { it.fqName?.asString() }
            ?: containingKtFile.packageFqName.asString()
    }
}
