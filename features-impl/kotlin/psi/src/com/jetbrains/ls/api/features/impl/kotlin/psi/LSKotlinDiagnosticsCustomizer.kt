// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.psi

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.LSDiagnosticsCustomizer
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DiagnosticSeverity
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.serialization.json.JsonElement
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.psi.KtFile

val kotlinInspectionPatcher: InspectionProfilePatcher = InspectionProfilePatcher(
    // Local inspections
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.RemoveRedundantQualifierNameInspection",
        reason = "LSP-703",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.KotlinUnusedImportInspection",
        reason = "LSP-704",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.UnusedVariableInspection",
        reason = "LSP-705",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection",
        reason = "LSP-706",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.RemoveExplicitTypeArgumentsInspection",
        reason = "LSP-707",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.K2MemberVisibilityCanBePrivateInspection",
        reason = "LSP-708",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.VariableNeverReadInspection",
        reason = "LSP-709",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection",
        reason = "LSP-710",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.PublicApiImplicitTypeInspection",
        reason = "LSP-711",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "org.jetbrains.kotlin.idea.codeInsight.inspections.UnusedSymbolInspection",
        reason = "LSP-1005",
    ),
    InspectionProfilePatcher.Patch.DisableSuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase",
        reason = "LSP-712",
    ),
    InspectionProfilePatcher.Patch.DisableSuperClass(
        fqcn = "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase",
        reason = "LSP-713",
    ),
    InspectionProfilePatcher.Patch.DisableClass(
        fqcn = "com.intellij.codeInspection.test.TestFailedLineInspection",
        // Needs TestStateStorage (persistent test-run history), which is meaningless in a headless
        // language server and collides ("storage already registered") across analysis contexts.
        reason = "LSP-1507; requires TestStateStorage; irrelevant and storage-conflicting in the language server",
    )
)

internal class LSKotlinDiagnosticsCustomizer : LSDiagnosticsCustomizer {
    override val inspectionProfilePatcher: InspectionProfilePatcher
        get() = kotlinInspectionPatcher

    override suspend fun getAdditionalDiagnostics(project: Project, params: DocumentDiagnosticParams): List<Diagnostic> {
        return lsKotlinCompilerDiagnostics(project, params)
    }
}

suspend fun lsKotlinCompilerDiagnostics(
    project: Project,
    params: DocumentDiagnosticParams,
    diagnosticDataFactory: (KaDiagnosticWithPsi<*>, VirtualFile) -> JsonElement? = { _, _ -> null },
): List<Diagnostic> = readAction {
    if (!params.textDocument.isSource()) return@readAction emptyList()

    val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
    val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
    if (!ProblemHighlightFilter.shouldHighlightFile(ktFile)) return@readAction emptyList()
    val document = virtualFile.findDocument() ?: return@readAction emptyList()
    analyze(ktFile) {
        ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).flatMap { diagnostic ->
            diagnostic.toLsp(document, diagnosticDataFactory(diagnostic, virtualFile))
        }
    }
}

private fun KaDiagnosticWithPsi<*>.toLsp(document: Document, data: JsonElement?): List<Diagnostic> {
    return textRanges.map { textRange ->
        Diagnostic(
            textRange.toLspRange(document),
            severity = severity.toLsp(),
            code = StringOrInt.string(factoryName),
            source = "Kotlin",
            message = defaultMessage,
            tags = emptyList(),
            data = data,
            relatedInformation = emptyList(),
        )
    }
}

private fun KaSeverity.toLsp(): DiagnosticSeverity = when (this) {
    KaSeverity.ERROR -> DiagnosticSeverity.Error
    KaSeverity.WARNING -> DiagnosticSeverity.Warning
    KaSeverity.INFO -> DiagnosticSeverity.Information
}
