// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.lsRunInspections
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement


// TODO: LSP-278 Optimize performance of inspections
class LSCommonInspectionDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
    quickFixBlacklist: Blacklist = Blacklist(),
) : LSDiagnosticProvider {
    private val lsInspectionManager = LSInspectionManager(quickFixBlacklist)
    
    companion object {
        val diagnosticSource: DiagnosticSource = DiagnosticSource("inspection")
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        val onTheFly = true
        val diagnostics = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            return@withAnalysisContextAndFileSettings lsRunInspections(params, project, onTheFly, inspectionProfilePatcher) { _, problemDescriptor ->
                LSP.json.encodeToJsonElement(lsInspectionManager.createDiagnosticData(problemDescriptor))
            }
        }
        diagnostics.forEach { diagnostic -> emit(diagnostic) }
    }
}
