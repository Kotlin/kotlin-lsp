// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.intellij.util.InjectionUtils
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.LSInjectedFile
import com.jetbrains.ls.api.core.features.lsCollectInjectedFiles
import com.jetbrains.ls.api.core.features.lsRunInspectionsOn
import com.jetbrains.ls.api.core.features.toHostLspRange
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DiagnosticSeverity
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job

/** Runs the inspections of the injected language over the files injected into a host file; no quick-fix data yet. */
class LSCommonInjectionInspectionDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
    private val fileFilter: (PsiFile) -> Boolean = { true },
) : LSDiagnosticProvider {
    private class InjectedFiles(val hostDocument: Document, val files: List<LSInjectedFile>)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val job = currentCoroutineContext().job
        val diagnostics = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            val injectedFiles = readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val hostDocument = virtualFile.findDocument() ?: return@readAction null
                val hostFile = virtualFile.findPsiFile(project) ?: return@readAction null
                if (!fileFilter(hostFile)) return@readAction null
                if (!ProblemHighlightFilter.shouldHighlightFile(hostFile)) return@readAction null
                if (!InjectionUtils.shouldInspectInjectedFiles(hostFile)) return@readAction null
                InjectedFiles(hostDocument, lsCollectInjectedFiles(hostFile, job))
            } ?: return@withAnalysisContextAndFileSettings emptyList()
            val injectedLanguageManager = InjectedLanguageManager.getInstance(project)
            injectedFiles.files.flatMap { injected -> runInspections(injected, injectedFiles.hostDocument, injectedLanguageManager) }
        }
        diagnostics.forEach { diagnostic -> emit(diagnostic) }
    }

    private suspend fun runInspections(
        injected: LSInjectedFile,
        hostDocument: Document,
        injectedLanguageManager: InjectedLanguageManager,
    ): List<Diagnostic> {
        val psiFile = injected.psiFile
        val lenient = readAction {
            if (!psiFile.isValid) return@readAction null
            injectedLanguageManager.shouldInspectionsBeLenient(psiFile) || injectedLanguageManager.isFrankensteinInjection(psiFile)
        } ?: return emptyList()
        val diagnostics = lsRunInspectionsOn(
            psiFile = psiFile,
            language = psiFile.language,
            toLspRange = { injected.toHostLspRange(it, hostDocument) },
            onTheFly = true,
            inspectionProfilePatcher = inspectionProfilePatcher,
            diagnosticDataFactory = { _, _ -> null },
        )
        // A lenient or Frankenstein injection has unknown text in or between its fragments, so only an error is trustworthy.
        return if (lenient) diagnostics.filter { it.severity == DiagnosticSeverity.Error } else diagnostics
    }
}
