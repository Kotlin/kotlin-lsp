// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.superDeclarations

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.serialization.Serializable

/**
 * Parameters of `intellij/superDeclarations`, the request behind Go to Super and the "overrides"/"implements" gutter icons.
 *
 * Without [enclosing] (gutter icons), each position must lie on the name of a declaration (inclusive of the name end); any
 * other position gets no targets. With [enclosing] (Go to Super at the caret), a position may lie anywhere inside a
 * declaration: it names the declaration that Go to Super of the monolith picks there (Java: the functional interface method of an
 * enclosing lambda or method reference, else the enclosing method or class; Kotlin: the enclosing function, property or class).
 */
@Serializable
data class SuperDeclarationsParams(
    val textDocument: TextDocumentIdentifier,
    val positions: List<Position>,
    val enclosing: Boolean = false,
)

/**
 * The direct super declarations of the declaration at one position: super methods of a method, super properties of a
 * Kotlin property, super classes and interfaces of a class (without `Object` / `Any`).
 */
@Serializable
data class SuperDeclarationsEntry(val targets: List<SuperDeclarationTarget>)

/**
 * One super declaration. [location] covers the name of the target; a library target has the URI `textDocument/definition`
 * gives it. [implements]: the declaration at the position implements this target (target abstract or expect, declaration not
 * abstract), the rule of the IDE gutter. The IDE shows "implementing" when all targets have it, else "overriding".
 */
@Serializable
data class SuperDeclarationTarget(val location: Location, val implements: Boolean)

/**
 * Gives one entry per position, in order; `null` when the file is not this provider's (for example, no PSI file of
 * its language).
 */
interface LSSuperDeclarationsProvider : LSLanguageSpecificConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getSuperDeclarations(params: SuperDeclarationsParams): List<SuperDeclarationsEntry>?
}

object LSSuperDeclarations {
    /** One entry per position, in order; entries without targets when no provider serves the file. */
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getSuperDeclarations(params: SuperDeclarationsParams): List<SuperDeclarationsEntry> {
        return configuration.entriesFor<LSSuperDeclarationsProvider>(params.textDocument)
                   .firstNotNullOfOrNull { it.getSuperDeclarations(params) }
               ?: params.positions.map { SuperDeclarationsEntry(emptyList()) }
    }
}
