// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.qualifiedName

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlinx.serialization.Serializable

/** Parameters of `intellij/qualifiedName`: the declaration or reference at [position] in [textDocument]. */
@Serializable
data class QualifiedNameParams(val textDocument: TextDocumentIdentifier, val position: Position)

/** Gives the qualified name that Copy Reference puts on the clipboard, e.g. `pkg.Foo#bar(int)` for a Java method. */
interface LSQualifiedNameProvider : LSConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getQualifiedName(params: QualifiedNameParams): String?
}

object LSQualifiedName {
    context(configuration: LSConfiguration, server: LSServer, handlerContext: LspHandlerContext)
    suspend fun getQualifiedName(params: QualifiedNameParams): String? {
        return configuration.entries<LSQualifiedNameProvider>().firstNotNullOfOrNull { it.getQualifiedName(params) }
    }
}
