// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.descendantsOfType
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DiagnosticSeverity
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSSyntaxErrorDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
) : LSDiagnosticProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        server.withAnalysisContext {
            readAction {
                val file = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = file.findDocument() ?: return@readAction emptyList()
                val psiFile = file.findPsiFile(project) ?: return@readAction emptyList()
                getSyntaxErrors(psiFile, document)
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }

    private fun getSyntaxErrors(psiFile: PsiFile, document: Document): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        for (errorElement in psiFile.descendantsOfType<PsiErrorElement>()) {
            diagnostics += Diagnostic(
                errorElement.textRange.toLspRange(document),
                severity = DiagnosticSeverity.Error,
                code = StringOrInt.string("SYNTAX"),
                message = errorElement.errorDescription,
            )
        }
        return diagnostics
    }
}
