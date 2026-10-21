// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.features.LSDocumentSymbolCustomizer
import com.jetbrains.ls.api.core.features.mapDeclaration
import com.jetbrains.ls.api.core.util.getLspLocation
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.inheritedMembers.InheritedMembersGroup
import com.jetbrains.ls.api.features.symbols.LSDocumentSymbolProvider
import com.jetbrains.lsp.protocol.TypeHierarchyItem

/**
 * The document symbol customizer of [language], from its [LSDocumentSymbolProviderPsiBase]; `null` when no such provider serves
 * the language.
 */
context(configuration: LSConfiguration)
fun lsDocumentSymbolCustomizer(language: Language): LSDocumentSymbolCustomizer<PsiElement>? {
    val lsLanguage = configuration.languageFor(language) ?: return null
    return configuration.entriesFor<LSDocumentSymbolProvider>(lsLanguage)
        .firstNotNullOfOrNull { (it as? LSDocumentSymbolProviderPsiBase)?.createCustomizer() }
}

/**
 * Groups [members] (in order) by the class [owner] gives, and maps each owner and member through the document symbol customizer
 * of its own language: a Kotlin class inherits Java members and the other way round. Owners and members go to their navigation
 * element first (Kotlin source for a light element, attached sources or the decompiled class for a library one), so a member's
 * ranges are valid in `supertype.uri`. A member without a symbol, or not in the file of its owner, is left out, and so is a group
 * without members. A member symbol has no children. Call it in a read action.
 */
context(configuration: LSConfiguration)
fun lsInheritedMembersGroups(members: List<PsiElement>, owner: (PsiElement) -> PsiElement?): List<InheritedMembersGroup> {
    val customizers = HashMap<Language, LSDocumentSymbolCustomizer<PsiElement>?>()
    fun customizerOf(element: PsiElement) = customizers.getOrPut(element.language) { lsDocumentSymbolCustomizer(element.language) }

    val membersByOwner = LinkedHashMap<PsiElement, MutableList<PsiElement>>()
    for (member in members) {
        val ownerElement = owner(member)?.navigationElement ?: continue
        membersByOwner.getOrPut(ownerElement) { mutableListOf() } += member.navigationElement
    }
    return membersByOwner.mapNotNull { (ownerElement, ownerMembers) ->
        val supertype = customizerOf(ownerElement)?.let { typeHierarchyItem(ownerElement, it) } ?: return@mapNotNull null
        val symbols = ownerMembers.distinct().mapNotNull { member ->
            if (member.containingFile != ownerElement.containingFile) return@mapNotNull null
            customizerOf(member)?.let { mapDeclaration(member, WithoutChildren(it)) }
        }
        symbols.takeIf { it.isNotEmpty() }?.let { InheritedMembersGroup(supertype, it) }
    }
}

/** The item of a supertype: name, kind and ranges as its document symbol has them; no `data`. */
private fun typeHierarchyItem(element: PsiElement, customizer: LSDocumentSymbolCustomizer<PsiElement>): TypeHierarchyItem? {
    val uri = element.getLspLocation()?.uri ?: return null
    val ranges = customizer.getRanges(element) ?: return null
    return TypeHierarchyItem(
        name = customizer.getName(element) ?: return null,
        kind = customizer.getKind(element) ?: return null,
        uri = uri,
        range = ranges.range,
        selectionRange = ranges.selectionRange,
    )
}

/** A member is one row: its nested declarations are not walked. */
private class WithoutChildren(customizer: LSDocumentSymbolCustomizer<PsiElement>) : LSDocumentSymbolCustomizer<PsiElement> by customizer {
    override fun getNestedDeclarations(element: PsiElement): List<PsiElement> = emptyList()
}
