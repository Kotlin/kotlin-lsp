// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.diagnostics

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProviderResults
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.DocumentDiagnosticReport
import com.jetbrains.lsp.protocol.DocumentDiagnosticReportKind

object LSDiagnostic {
    val scope: Scope = Scope("lsp.diagnostic")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getDiagnostics(params: DocumentDiagnosticParams): DocumentDiagnosticReport {
        // partial results in diagnotics, according to the LSP spec (https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#documentDiagnosticReportPartialResult),
        // support only partial results for related diagnostics, not for the diagnostics for the current document,
        // so we just collect results concurrently from all handlers
        val diagnostics = LSConcurrentResponseHandler.respondDirectlyWithResultsCollectedConcurrently(
            providers = configuration.entriesFor<LSDiagnosticProvider>(params.textDocument),
            getResults = { diagnosticProvider ->
                tracer.traceProviderResults(
                    spanName = "provider.diagnostic",
                    provider = diagnosticProvider,
                    results = diagnosticProvider.getDiagnostics(params),
                )
            },
        )

        return DocumentDiagnosticReport(
            DocumentDiagnosticReportKind.Full,
            resultId = null,
            items = diagnostics,
            relatedDocuments = null,
        )
    }
}
