// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.LSInspections
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The quick fixes of an inspection diagnostic.
 *
 * A diagnostic of the editor carries its fixes in its data. A diagnostic of a batch run
 * ([LSCommonInspectionDiagnosticProvider.batchDiagnosticSource]) carries none: this provider runs the inspection named by
 * its code on the file again, in batch mode as the run did, and offers the fixes of the problem with the same range and
 * message. When the file changed since the run, no problem matches and there are no fixes.
 *
 * [inspectionProfilePatcher] and [quickFixBlacklist] must be the ones of the diagnostic provider, so the second run
 * reports the same problems with the same fixes.
 */
class LSCommonInspectionFixesCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
    quickFixBlacklist: Blacklist = Blacklist(),
) : LSCodeActionProvider {
    private val lsInspectionManager = LSInspectionManager(quickFixBlacklist)

    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val diagnostics = params.diagnosticData<SimpleDiagnosticData>()
        diagnostics
            .filter { it.data.diagnosticSource == LSCommonInspectionDiagnosticProvider.diagnosticSource }
            .flatMap { data ->
                data.data.fixes.map { quickFix ->
                    applyFixCodeAction(quickFix.name, CodeActionKind.QuickFix, quickFix.modCommandData, data.diagnostic)
                }
            }
            .forEach { codeAction -> emit(codeAction) }

        val batchDiagnostics = diagnostics
            .filter { it.data.diagnosticSource == LSCommonInspectionDiagnosticProvider.batchDiagnosticSource }
            .map { it.diagnostic }
        if (batchDiagnostics.isNotEmpty() && params.textDocument.isSource()) {
            batchFixes(params, batchDiagnostics).forEach { codeAction -> emit(codeAction) }
        }
    }

    /** Runs the inspections of [diagnostics] on the file again and gives the fixes of the problems that match them. */
    context(server: LSServer)
    private suspend fun batchFixes(params: CodeActionParams, diagnostics: List<Diagnostic>): List<CodeAction> {
        val inspectionIds = diagnostics.mapNotNullTo(mutableSetOf()) { it.code?.value?.content }
        if (inspectionIds.isEmpty()) return emptyList()
        val problems = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            val psiFile = readAction {
                params.textDocument.findVirtualFile()?.findPsiFile(project)
            } ?: return@withAnalysisContextAndFileSettings emptyList()
            val document = readAction { psiFile.fileDocument }
            LSInspections.runInspectionsOn(
                psiFile = psiFile,
                language = psiFile.language,
                toLspRange = { it.toLspRange(document) },
                onTheFly = false,
                inspectionProfilePatcher = inspectionProfilePatcher,
                importInspectionProfile = server.config.importInspectionProfile,
                diagnosticDataFactory = { _, problemDescriptor ->
                    LSP.json.encodeToJsonElement(lsInspectionManager.createDiagnosticData(problemDescriptor))
                },
                inspectionIds = inspectionIds,
            )
        }
        return diagnostics.flatMap { diagnostic ->
            val problem = problems.firstOrNull {
                it.code == diagnostic.code && it.range == diagnostic.range && it.message == diagnostic.message
            } ?: return@flatMap emptyList()
            problem.diagnosticData<SimpleDiagnosticData>()?.fixes.orEmpty().map { quickFix ->
                applyFixCodeAction(quickFix.name, CodeActionKind.QuickFix, quickFix.modCommandData, diagnostic)
            }
        }
    }
}
