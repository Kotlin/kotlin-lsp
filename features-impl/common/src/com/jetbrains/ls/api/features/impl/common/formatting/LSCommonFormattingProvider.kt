// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.formatting

import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.codeStyle.CodeStyleManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.formatting.LSFormattingProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.PsiFileTextEditsCollector
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentFormattingParams
import com.jetbrains.lsp.protocol.DocumentRangeFormattingParams
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit

class LSCommonFormattingProvider(
    override val supportedLanguages: Set<LSLanguage>
) : LSFormattingProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getFormatting(params: DocumentFormattingParams): List<TextEdit>? {
        return format(params.textDocument, range = null)
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getFormattingRanged(params: DocumentRangeFormattingParams): List<TextEdit>? {
        return format(params.textDocument, params.range)
    }

    context(server: LSServer)
    private suspend fun format(textDocument: TextDocumentIdentifier, range: Range?): List<TextEdit>? {
        return server.withAnalysisContextAndFileSettings(textDocument.uri.uri) {
            val virtualFile = readAction { textDocument.findVirtualFile() } ?: return@withAnalysisContextAndFileSettings null
            val document = readAction { virtualFile.findDocument() } ?: return@withAnalysisContextAndFileSettings null
            PsiFileTextEditsCollector.collectTextEdits(virtualFile) { psiFile ->
                val textRange = when (range) {
                    null -> TextRange(0, psiFile.textLength)
                    else -> range.toTextRange(document)
                }
                val codeStyleManager = CodeStyleManager.getInstance(project)
                codeStyleManager.reformatText(psiFile, listOf(textRange))
            }
        }
    }
}
