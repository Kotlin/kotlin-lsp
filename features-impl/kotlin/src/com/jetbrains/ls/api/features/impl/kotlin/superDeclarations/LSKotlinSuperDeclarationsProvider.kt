// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.superDeclarations

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.getLspLocationForDefinition
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.superDeclarations.LSSuperDeclarationsProvider
import com.jetbrains.ls.api.features.superDeclarations.SuperDeclarationTarget
import com.jetbrains.ls.api.features.superDeclarations.SuperDeclarationsEntry
import com.jetbrains.ls.api.features.superDeclarations.SuperDeclarationsParams
import com.jetbrains.lsp.implementation.LspHandlerContext
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.directlyOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.symbols.getExpectsForActual
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.codeInsight.SuperDeclarationProvider
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Kotlin super declarations through the K2 Go to Super code (`KotlinGoToSuperDeclarationsHandler`): the declaration from
 * [SuperDeclarationProvider.findDeclaration] (a function, a class or object, a property, a `val`/`var` parameter) and its
 * direct supers from [SuperDeclarationProvider.findSuperDeclarations] (overridden symbols, super types without `Any`, the
 * `expect` of an `actual`). Whether the declaration implements a target follows the Kotlin up-arrow gutter ([implementedSupers]).
 */
internal object LSKotlinSuperDeclarationsProvider : LSSuperDeclarationsProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getSuperDeclarations(params: SuperDeclarationsParams): List<SuperDeclarationsEntry>? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val declarations = params.positions.map { declarationAt(psiFile, document.offsetByPosition(it), params.enclosing) }
                val supers = declarations.filterNotNull().distinct().associateWith { declaration ->
                    val implemented = implementedSupers(declaration)
                    SuperDeclarationProvider.findSuperDeclarations(declaration).mapNotNull { superDeclaration ->
                        val element = superDeclaration.declaration.element ?: return@mapNotNull null
                        val location = element.getLspLocationForDefinition() ?: return@mapNotNull null
                        SuperDeclarationTarget(location, implemented.any { it.isEquivalentTo(element) })
                    }
                }
                declarations.map { SuperDeclarationsEntry(it?.let { supers[it] }.orEmpty()) }
            }
        }
    }

    /**
     * The supers [declaration] implements rather than overrides, by the rule of the Kotlin up-arrow gutter
     * (`KotlinLineMarkerProvider`: the "implementing" icon, `SuperDeclarationMarkerTooltip`: "Implements"): a direct super with
     * the abstract modality or an `expect`, unless the declaration is abstract itself.
     */
    private fun implementedSupers(declaration: KtDeclaration): List<PsiElement> = analyze(declaration) {
        var symbol: KaDeclarationSymbol = declaration.symbol
        if (symbol is KaValueParameterSymbol) symbol = symbol.primaryConstructorProperty ?: return@analyze emptyList()
        if (symbol.modality == KaSymbolModality.ABSTRACT) return@analyze emptyList()
        val supers: Sequence<KaDeclarationSymbol> = when (symbol) {
            is KaCallableSymbol -> symbol.directlyOverriddenSymbols
            is KaClassSymbol -> symbol.superTypes.asSequence().mapNotNull { (it as? KaClassType)?.symbol }
            else -> emptySequence()
        }
        (supers.filter { it.modality == KaSymbolModality.ABSTRACT } + symbol.getExpectsForActual()).mapNotNull { it.psi }.toList()
    }

    /** The declaration Go to Super picks at [offset]; without [enclosing], only when its name holds [offset]. */
    private fun declarationAt(file: PsiFile, offset: Int, enclosing: Boolean): KtDeclaration? {
        val leaf = file.findElementAt(offset) ?: (if (offset > 0) file.findElementAt(offset - 1) else null) ?: return null
        val declaration = SuperDeclarationProvider.findDeclaration(leaf) ?: return null
        if (enclosing) return declaration
        val name = (declaration as? KtNamedDeclaration)?.nameIdentifier ?: return null
        return declaration.takeIf { name.textRange.containsOffset(offset) }
    }
}
