// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.diagnostics

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import kotlinx.coroutines.flow.Flow

interface LSDiagnosticProvider : LSLanguageSpecificConfigurationEntry {
    // todo handle other parts of `DocumentDiagnosticReport`
    context(_: LSServer)
    fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic>
}