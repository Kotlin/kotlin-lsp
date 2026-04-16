// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.foldingRange

import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.foldingRange.LSFoldingRangeProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FoldingRange
import com.jetbrains.lsp.protocol.FoldingRangeKind
import com.jetbrains.lsp.protocol.FoldingRangeParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonFoldingRangeProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val getFoldingRangeKind: (PsiElement?) -> FoldingRangeKind? = { FoldingRangeKind.Region },
) : LSFoldingRangeProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideFoldingRanges(params: FoldingRangeParams): Flow<FoldingRange> = flow {
        server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()

                val builder = LanguageFolding.INSTANCE.forLanguage(psiFile.language) ?: return@readAction emptyList()
                psiFile.node // load AST, some builders don't work without that
                val descriptors = LanguageFolding.buildFoldingDescriptors(builder, psiFile, document, false)
                descriptors.map { descriptor ->
                    val lspRange = descriptor.range.toLspRange(document)
                    val kind = getFoldingRangeKind(descriptor.element.psi)
                    FoldingRange(
                        startLine = lspRange.start.line,
                        endLine = lspRange.end.line,
                        startCharacter = lspRange.start.character,
                        endCharacter = lspRange.end.character,
                        kind = kind,
                        collapsedText = descriptor.placeholderText
                    )
                }.sortedBy { it.startLine }.distinct()
            }
        }.forEach { emit(it) }
    }
}
