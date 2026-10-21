// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.inheritedMembers

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMember
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.impl.common.symbols.lsInheritedMembersGroups
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.inheritedMembers.InheritedMembersGroup
import com.jetbrains.ls.api.features.inheritedMembers.InheritedMembersParams
import com.jetbrains.ls.api.features.inheritedMembers.LSInheritedMembersProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import org.jetbrains.kotlin.analysis.api.scopes.declaredMemberScope
import org.jetbrains.kotlin.analysis.api.scopes.memberScope
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Kotlin inherited members with the rules of the K2 structure view (`KotlinFirInheritedMembersNodeProvider`): the member scope
 * minus the declared member scope, without intersection overrides and classes. A member may be a Kotlin or a Java declaration,
 * from the project or a library. Grouped by the class that holds each member.
 */
internal object LSKotlinInheritedMembersProvider : LSInheritedMembersProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getInheritedMembers(params: InheritedMembersParams): List<InheritedMembersGroup>? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val classOrObject = classAt(psiFile, document.offsetByPosition(params.position)) ?: return@readAction null
                lsInheritedMembersGroups(inheritedMembers(classOrObject)) { member ->
                    (member as? PsiMember)?.containingClass ?: PsiTreeUtil.getParentOfType(member, KtClassOrObject::class.java)
                }
            }
        }
    }

    /**
     * The class or object whose name holds [offset]. An object without a name (a companion) has its document symbol selection at its
     * start, so only that offset names it.
     */
    private fun classAt(file: PsiFile, offset: Int): KtClassOrObject? {
        val leaf = file.findElementAt(offset) ?: (if (offset > 0) file.findElementAt(offset - 1) else null) ?: return null
        val classOrObject = PsiTreeUtil.getNonStrictParentOfType(leaf, KtClassOrObject::class.java) ?: return null
        val name = classOrObject.nameIdentifier
        val matches = if (name != null) name.textRange.containsOffset(offset) else classOrObject.textRange.startOffset == offset
        return classOrObject.takeIf { matches }
    }

    private fun inheritedMembers(classOrObject: KtClassOrObject): List<PsiElement> = analyze(classOrObject) {
        val classSymbol = classOrObject.symbol as? KaClassSymbol ?: return@analyze emptyList()
        val declared = classSymbol.declaredMemberScope.declarations.toSet()
        classSymbol.memberScope.declarations
            .filter { it !in declared && it.origin != KaSymbolOrigin.INTERSECTION_OVERRIDE && it !is KaClassSymbol }
            .mapNotNull { it.psi as? NavigatablePsiElement }
            .toList()
    }
}
