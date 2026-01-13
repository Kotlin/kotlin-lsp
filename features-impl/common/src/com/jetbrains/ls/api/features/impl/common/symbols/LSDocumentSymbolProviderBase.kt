// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.symbols.LSDocumentSymbolProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolParams
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.SymbolKind
import com.jetbrains.lsp.protocol.SymbolTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

abstract class LSDocumentSymbolProviderBase<T> : LSDocumentSymbolProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDocumentSymbols(params: DocumentSymbolParams): Flow<DocumentSymbol> = flow {
        val uri = params.textDocument.uri.uri
        server.withAnalysisContext {
            readAction {
                uri.findVirtualFile()
                    ?.findPsiFile(project)
                    ?.let { psiFile -> getRootDeclarations(psiFile) }
                    ?.mapNotNull { declaration ->
                        mapDeclaration(declaration)
                    } ?: emptyList()
            }
        }.forEach { documentSymbol -> emit(documentSymbol) }
    }

    context(analysisContext: LSAnalysisContext)
    protected open fun mapDeclaration(element: T): DocumentSymbol? {
        val name = getName(element) ?: return null
        val kind = getKind(element) ?: return null
        val ranges = getRanges(element) ?: return null
        val deprecated = isDeprecated(element)
        return DocumentSymbol(
            name = name,
            detail = null, // TODO: calculate signatures
            kind = kind,
            tags = if (deprecated) listOf(SymbolTag.Deprecated) else null,
            deprecated = deprecated,
            range = ranges.range,
            selectionRange = ranges.selectionRange,
            children = getNestedDeclarations(element).mapNotNull { mapDeclaration(it) }.takeIf { it.isNotEmpty() }
        )
    }



    data class ElementRanges(val range: Range, val selectionRange: Range)

    protected abstract fun getRanges(element: T): ElementRanges?
    protected abstract fun getName(element: T): String?
    protected abstract fun getKind(element: T): SymbolKind?
    protected abstract fun isDeprecated(element: T): Boolean
    protected abstract fun getRootDeclarations(psiFile: PsiFile): List<T>
    protected abstract fun getNestedDeclarations(element: T): List<T>
}