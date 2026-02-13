// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinCompilerDiagnosticsProvider : LSDiagnosticProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val uri = params.textDocument.uri.uri
        server.withAnalysisContext {
            readAction {
                val virtualFile = uri.findVirtualFile() ?: return@readAction emptyList()
                val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                analyze(ktFile) {
                    val diagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                    diagnostics.flatMap { it.toLsp(document, virtualFile) }
                }
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }
}

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
