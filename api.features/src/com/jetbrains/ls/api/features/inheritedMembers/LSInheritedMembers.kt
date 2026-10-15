// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inheritedMembers

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextDocumentPositionParams
import com.jetbrains.lsp.protocol.TypeHierarchyItem
import kotlinx.serialization.Serializable

/**
 * Parameters of `intellij/inheritedMembers`, the request behind "Show Inherited" in the structure view. [position] must be in the
 * `selectionRange` of a class document symbol, end included: the class name, or the base class reference of an anonymous class.
 * Any other position gets no result.
 */
@Serializable
data class InheritedMembersParams(
    override val textDocument: TextDocumentIdentifier,
    override val position: Position,
) : TextDocumentPositionParams

/**
 * The members a class inherits from one [supertype], as the monolith structure view lists them. Each member is a document symbol
 * of its own language, without children. Its ranges are in `supertype.uri`, which may be a library file.
 */
@Serializable
data class InheritedMembersGroup(val supertype: TypeHierarchyItem, val members: List<DocumentSymbol>)

/**
 * Returns one group per supertype that contributes members, in the monolith order. Own members, overridden members, constructors
 * and members the class cannot access are left out. Returns an empty list when the class inherits nothing, and `null` when the
 * file is not this provider's or no class is at the position.
 */
interface LSInheritedMembersProvider : LSLanguageSpecificConfigurationEntry {
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getInheritedMembers(params: InheritedMembersParams): List<InheritedMembersGroup>?
}

object LSInheritedMembers {
    /** The groups of the class at the position; `null` when no provider serves the file or there is no class there. */
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getInheritedMembers(params: InheritedMembersParams): List<InheritedMembersGroup>? {
        return configuration.entriesFor<LSInheritedMembersProvider>(params.textDocument)
            .firstNotNullOfOrNull { it.getInheritedMembers(params) }
    }
}
