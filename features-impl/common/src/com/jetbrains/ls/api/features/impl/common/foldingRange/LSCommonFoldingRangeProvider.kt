// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.foldingRange

import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
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

/**
 * @param keepClosingBracketLine when `true` and the client folds whole lines only, as VS Code does, a bracket pair range
 * such as a `{...}` block whose closing bracket is alone on its line ends on the line before it, so the bracket stays
 * visible. A range that has no line left is dropped. A client that folds by characters gets the ranges unchanged.
 */
class LSCommonFoldingRangeProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val keepClosingBracketLine: Boolean = false,
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
                descriptors.mapNotNull { descriptor ->
                    val lspRange = descriptor.range.toLspRange(document)
                    val kind = getFoldingRangeKind(descriptor.element.psi)
                    val range = FoldingRange(
                        startLine = lspRange.start.line,
                        endLine = lspRange.end.line,
                        startCharacter = lspRange.start.character,
                        endCharacter = lspRange.end.character,
                        kind = kind,
                        collapsedText = descriptor.placeholderText
                    )
                    if (keepClosingBracketLine && server.config.clientLineFoldingOnly &&
                        endsWithOwnClosingBracketLine(descriptor.range, document)) {
                        range.beforeLastLine(document)
                    }
                    else {
                        range
                    }
                }.sortedBy { it.startLine }.distinct()
            }
        }.forEach { emit(it) }
    }
}

/**
 * `true` when [range] is a bracket pair and its closing bracket is alone on the last line.
 * A range that only ends with a nested block, such as a `case` body that ends with an `if` block, is not a bracket pair.
 */
private fun endsWithOwnClosingBracketLine(range: TextRange, document: Document): Boolean {
    if (range.length < 2) return false
    val text = document.charsSequence
    if (BRACKET_PAIRS[text[range.startOffset]] != text[range.endOffset - 1]) return false
    val lineStart = document.getLineStartOffset(document.getLineNumber(range.endOffset))
    return text.subSequence(lineStart, range.endOffset).trim().length == 1
}

private fun FoldingRange.beforeLastLine(document: Document): FoldingRange? {
    val newEndLine = endLine - 1
    if (newEndLine <= startLine) return null
    val lineLength = document.getLineEndOffset(newEndLine) - document.getLineStartOffset(newEndLine)
    return copy(endLine = newEndLine, endCharacter = lineLength)
}

private val BRACKET_PAIRS = mapOf('{' to '}', '(' to ')', '[' to ']')
