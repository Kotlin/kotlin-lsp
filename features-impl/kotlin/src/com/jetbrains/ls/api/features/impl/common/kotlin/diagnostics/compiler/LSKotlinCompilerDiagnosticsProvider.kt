// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinCompilerDiagnosticsProvider : LSDiagnosticProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(LSServer)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        val uri = params.textDocument.uri.uri
        withAnalysisContext {
            val file = uri.findVirtualFile() ?: return@withAnalysisContext emptyList()
            val ktFile = file.findPsiFile(project) as? KtFile ?: return@withAnalysisContext emptyList()
            val document = file.findDocument() ?: return@withAnalysisContext emptyList()
            analyze(ktFile) {
                val diagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                diagnostics.flatMap { it.toLsp(document, file) }
            }
        }.forEach { emit(it) }
    }
}

context(KaSession, LSAnalysisContext, LSServer)
private fun KaDiagnosticWithPsi<*>.toLsp(document: Document, file: VirtualFile): List<Diagnostic> {
    val data = KotlinCompilerDiagnosticData.create(this, file)
    return textRanges.map { textRange ->
        Diagnostic(
            textRange.toLspRange(document),
            severity = severity.toLsp(),
            code = StringOrInt.string(factoryName),
            source = "Kotlin",
            message = defaultMessage,
            tags = emptyList(),
            data = LSP.json.encodeToJsonElement(data),
            relatedInformation = emptyList(),
        )
    }
}