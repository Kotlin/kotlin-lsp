// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.expressionType

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.serialization.Serializable

/**
 * Parameters of `intellij/expressionType`, the request behind Expression Type Info.
 *
 * [position] is the caret: it picks the injection, the language and, without a [range], the offset. [range] is the raw
 * editor selection (the server trims its whitespace), or the candidate range a client asks the [advanced] hint for.
 */
@Serializable
data class ExpressionTypeParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val range: Range? = null,
    val advanced: Boolean = false,
)

/**
 * One candidate expression: its [range] in the host document, its type as an HTML [hint], and whether its provider has
 * an advanced hint (Java: the dataflow table) to show when the user asks again.
 */
@Serializable
data class ExpressionType(val range: Range, val hint: String, val hasAdvanced: Boolean)

/**
 * Gives the expressions Expression Type Info offers at a position, innermost first; `null` when no provider serves the
 * languages there, empty when the providers find no expression.
 */
interface LSExpressionTypeProvider : LSConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getExpressionTypes(params: ExpressionTypeParams): List<ExpressionType>?
}

object LSExpressionType {
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getExpressionTypes(params: ExpressionTypeParams): List<ExpressionType>? {
        return configuration.entries<LSExpressionTypeProvider>().firstNotNullOfOrNull { it.getExpressionTypes(params) }
    }
}
