// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.symbols

import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocation
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.ls.api.features.symbols.LSDocumentSymbolProvider
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolParams
import com.jetbrains.lsp.protocol.SymbolKind
import com.jetbrains.lsp.protocol.SymbolTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

abstract class AbstractLSDocumentSymbolProvider : LSDocumentSymbolProvider {
    context(LSServer)
    override fun getDocumentSymbols(params: DocumentSymbolParams): Flow<DocumentSymbol> = channelFlow {
        val uri = params.textDocument.uri.uri
        withAnalysisContext(uri) {
            uri.findVirtualFile()
                ?.findPsiFile(project)
                ?.let { getNestedDeclarations(it) }
                ?.forEach { declaration ->
                    mapDeclaration(declaration)?.let { channel.send(it) }
                }
        }
    }

    context(LSAnalysisContext)
    private fun mapDeclaration(element: PsiElement): DocumentSymbol? {
        val name = getName(element) ?: return null
        val kind = getKind(element) ?: return null
        val range = element.getLspLocation()?.range ?: return null
        val selectionRange = element.getLspLocationForDefinition()?.range ?: return null
        val deprecated = isDeprecated(element)
        return DocumentSymbol(
            name = name,
            detail = null, // TODO: calculate signatures
            kind = kind,
            tags = if (deprecated) listOf(SymbolTag.Deprecated) else null,
            deprecated = deprecated,
            range = range,
            selectionRange = selectionRange,
            children = getNestedDeclarations(element).mapNotNull { mapDeclaration(it) }.takeIf { it.isNotEmpty() }
        )
    }

    protected open fun getName(element: PsiElement): String? =
        when (element) {
            is PsiNameIdentifierOwner -> element.name
            else -> null
        }
    protected abstract fun getKind(element: PsiElement): SymbolKind?
    protected abstract fun isDeprecated(element: PsiElement): Boolean
    protected abstract fun getNestedDeclarations(element: PsiElement): List<PsiElement>
}