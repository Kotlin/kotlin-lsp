// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.diagnostics

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.DocumentDiagnosticReport
import com.jetbrains.lsp.protocol.DocumentDiagnosticReportKind

object LSDiagnostic {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getDiagnostics(params: DocumentDiagnosticParams): DocumentDiagnosticReport {
        // partial results in diagnotics, according to the LSP spec (https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#documentDiagnosticReportPartialResult),
        // support only partial results for related diagnostics, not for the diagnostics for the current document,
        // so we just collect results concurrently from all handlers
        val diagnostics = LSConcurrentResponseHandler.respondDirectlyWithResultsCollectedConcurrently(
            providers = entriesFor<LSDiagnosticProvider>(params.textDocument),
            getResults = { diagnosticProvider -> diagnosticProvider.getDiagnostics(params) },
        ).sortedBy { it.range.start.line }

        return DocumentDiagnosticReport(
            DocumentDiagnosticReportKind.Full,
            resultId = null,
            items = diagnostics,
            relatedDocuments = null,
        )
    }
}
