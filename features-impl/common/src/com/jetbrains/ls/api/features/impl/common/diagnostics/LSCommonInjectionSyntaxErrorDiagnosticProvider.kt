// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.lsCollectInjectedFiles
import com.jetbrains.ls.api.core.features.lsGetSyntaxErrors
import com.jetbrains.ls.api.core.features.toHostLspRange
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job

/** Reports the syntax errors of the files injected into a host file at their host ranges. */
class LSCommonInjectionSyntaxErrorDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val fileFilter: (PsiFile) -> Boolean = { true },
) : LSDiagnosticProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val job = currentCoroutineContext().job
        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val hostDocument = virtualFile.findDocument() ?: return@readAction emptyList()
                val hostFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                if (!fileFilter(hostFile)) return@readAction emptyList()
                val injectedLanguageManager = InjectedLanguageManager.getInstance(project)
                lsCollectInjectedFiles(hostFile, job).flatMap { injected ->
                    if (injectedLanguageManager.isFrankensteinInjection(injected.psiFile)) return@flatMap emptyList()
                    lsGetSyntaxErrors(injected.psiFile, job) { range -> injected.toHostLspRange(range, hostDocument) }
                }
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }
}
