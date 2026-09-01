// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.lsGetSyntaxErrors
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonSyntaxErrorDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val fileFilter: (PsiFile) -> Boolean = { true },
) : LSDiagnosticProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            lsGetSyntaxErrors(project, params, fileFilter)
        }.forEach { diagnostic -> emit(diagnostic) }
    }
}
