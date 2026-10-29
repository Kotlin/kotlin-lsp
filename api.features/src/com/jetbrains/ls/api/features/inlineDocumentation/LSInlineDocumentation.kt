// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inlineDocumentation

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.serialization.Serializable

/**
 * Parameters of `intellij/inlineDocumentation`, the request behind rendered doc comments (reader mode).
 *
 * Without [renderText] the server sends the ranges only and leaves [InlineDocumentationItem.html] `null`.
 */
@Serializable
data class InlineDocumentationParams(
    val textDocument: TextDocumentIdentifier,
    val renderText: Boolean = true,
)

/**
 * One doc comment of a file: the [range] of the comment, the [ownerRange] of the declaration it documents, and the
 * comment rendered as IDEA renders it inline ([html], with `psi_element://` links), `null` when not asked for or empty.
 */
@Serializable
data class InlineDocumentationItem(
    val range: Range,
    val ownerRange: Range?,
    val html: String?,
)

/**
 * Parameters of `intellij/resolveDocLink`: the [url] of a link in rendered documentation, and the [range] it was rendered
 * for, either a comment range from `intellij/inlineDocumentation` or the location of a previous [DocLinkResolution].
 */
@Serializable
data class ResolveDocLinkParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val url: String,
)

/**
 * The target of a documentation link: its quick documentation [html], the [location] of its declaration (`null` for a
 * target without one), and its presentable [name].
 */
@Serializable
data class DocLinkResolution(
    val html: String?,
    val location: Location?,
    val name: String?,
)

interface LSInlineDocumentationProvider : LSConfigurationEntry {
    /** The doc comments of a file, in document order; `null` when the provider does not serve the file. */
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun inlineDocumentation(params: InlineDocumentationParams): List<InlineDocumentationItem>?

    /** The target of a documentation link, or `null` when the link does not resolve. */
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun resolveDocLink(params: ResolveDocLinkParams): DocLinkResolution?
}

object LSInlineDocumentation {
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun inlineDocumentation(params: InlineDocumentationParams): List<InlineDocumentationItem>? {
        return configuration.entries<LSInlineDocumentationProvider>().firstNotNullOfOrNull { it.inlineDocumentation(params) }
    }

    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun resolveDocLink(params: ResolveDocLinkParams): DocLinkResolution? {
        return configuration.entries<LSInlineDocumentationProvider>().firstNotNullOfOrNull { it.resolveDocLink(params) }
    }
}
