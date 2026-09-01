// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSCompilationDiagnosticProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.psi.lsKotlinCompilerDiagnostics
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

internal object LSKotlinCompilerDiagnosticsProvider : LSCompilationDiagnosticProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

  context(server: LSServer, handlerContext: LspHandlerContext)
  override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
    val uri = params.textDocument.uri.uri
    server.withAnalysisContextAndFileSettings(uri) {
      lsKotlinCompilerDiagnostics(project, params) { diagnostic, virtualFile ->
        LSP.json.encodeToJsonElement(KotlinCompilerDiagnosticData.create(diagnostic, virtualFile))
      }
    }.forEach { diagnostic -> emit(diagnostic) }
  }
}
