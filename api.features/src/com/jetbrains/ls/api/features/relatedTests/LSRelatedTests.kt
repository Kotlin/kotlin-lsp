// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.relatedTests

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.serialization.Serializable

/** Parameters of `intellij/relatedTests`, the request behind Go to Test: the caret of the user. */
@Serializable
data class RelatedTestsParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

/**
 * The answer of Go to Test at one position, with the rules of the monolith (`GotoTestOrCodeHandler`).
 *
 * [isTest] says whether the code at the position is a test (a test framework recognizes its class), so [targets] are
 * the tested classes; otherwise [targets] are the tests of the code. [sourceName] is the name of the class, file or
 * top-level declaration the search started from (the monolith chooser title "Choose Test for X"); `null` when no such
 * element is at the position, and then [targets] is empty. Each [Location] covers the name of the target, or is empty
 * at the start of a target file; the order is the monolith order (closest name first).
 */
@Serializable
data class RelatedTestsResult(
    val isTest: Boolean,
    val targets: List<Location>,
    val sourceName: String? = null,
)

/** Gives `null` when the file is not this provider's (for example, no PSI file of its language). */
interface LSRelatedTestsProvider : LSLanguageSpecificConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getRelatedTests(params: RelatedTestsParams): RelatedTestsResult?
}

object LSRelatedTests {
    /** No targets when no provider serves the file. */
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getRelatedTests(params: RelatedTestsParams): RelatedTestsResult {
        return configuration.entriesFor<LSRelatedTestsProvider>(params.textDocument)
                   .firstNotNullOfOrNull { it.getRelatedTests(params) }
               ?: RelatedTestsResult(isTest = false, targets = emptyList())
    }
}
