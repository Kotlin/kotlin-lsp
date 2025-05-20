package com.jetbrains.ls.api.features.diagnostics

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import kotlinx.coroutines.flow.Flow

interface LSDiagnosticProvider : LSLanguageSpecificConfigurationEntry {
    // todo handle other parts of `DocumentDiagnosticReport`
    context(LSServer)
    fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic>
}