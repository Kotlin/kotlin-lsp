// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.SemanticTokensParams
import com.jetbrains.lsp.protocol.SemanticTokensRangeParams
import com.jetbrains.lsp.protocol.TextDocumentIdentifier

abstract class LSSemanticTokensProviderBase : LSSemanticTokensProvider {
    abstract val supportedTokenTypes: List<LSSemanticTokenTypePredefined>
    abstract val supportedTokenModifiers: List<LSSemanticTokenModifierPredefined>

    /**
     * @param documentRange `null` means tokens from the whole file`
     */
    context(server: LSServer)
    protected abstract fun getSemanticTokens(psiFile: PsiFile, document: Document, documentRange: Range?): List<LSSemanticTokenWithRange>

    override fun createRegistry(): LSSemanticTokenRegistry {
        return LSSemanticTokenRegistry(supportedTokenTypes, supportedTokenModifiers)
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun full(params: SemanticTokensParams): List<LSSemanticTokenWithRange> {
        return getTokens(params.textDocument, range = null)
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun range(params: SemanticTokensRangeParams): List<LSSemanticTokenWithRange> {
        return getTokens(params.textDocument, params.range)
    }

    context(server: LSServer)
    private suspend fun getTokens(textDocument: TextDocumentIdentifier, range: Range?): List<LSSemanticTokenWithRange> {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = textDocument.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project)
                if (psiFile == null) return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                getSemanticTokens(psiFile, document, range)
            }
        }
    }
}
