// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.diagnostics

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Provides diagnostics (known as inspections in IntelliJ).
 */
interface LSDiagnosticProvider : LSLanguageSpecificConfigurationEntry {
    // TODO LSP-237 handle other parts of `DocumentDiagnosticReport`
    context(server: LSServer, handlerContext: LspHandlerContext)
    fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic>
}

/**
 * Provides compiler errors. [getDiagnostics] reports them to the editor together with their fixes.
 */
interface LSCompilationDiagnosticProvider : LSDiagnosticProvider {
    /**
     * The compiler errors of the document without [Diagnostic.data], for a caller that offers no fixes. A provider
     * that puts fixes into the data must not build them here: a lazy fix stays registered and holds PSI, so a run
     * over many files would push the fixes of the open editors out of the registry.
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    fun getCompilationErrors(params: DocumentDiagnosticParams): Flow<Diagnostic> =
        getDiagnostics(params).map { it.copy(data = null) }
}
