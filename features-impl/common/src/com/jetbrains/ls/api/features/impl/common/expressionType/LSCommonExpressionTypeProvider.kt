// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.expressionType

import com.intellij.codeInsight.hint.ShowExpressionTypeHandler
import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.injectedToHostRange
import com.jetbrains.ls.api.core.features.lsFindInjectedFileAt
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.expressionType.ExpressionType
import com.jetbrains.ls.api.features.expressionType.ExpressionTypeParams
import com.jetbrains.ls.api.features.expressionType.LSExpressionTypeProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import kotlin.math.max

/**
 * Serves every language through the `codeInsight.typeInfo` extensions the server ships, with the candidate rules of
 * [ShowExpressionTypeHandler.getExpressions]. Inside an injection the injected file answers when its language has a
 * provider and both ends of the selection lie in its fragments; otherwise the host file does. Ranges are in the host document.
 */
object LSCommonExpressionTypeProvider : LSExpressionTypeProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getExpressionTypes(params: ExpressionTypeParams): List<ExpressionType>? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val hostFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val hostDocument = virtualFile.findDocument() ?: return@readAction null
                val caret = hostDocument.offsetByPosition(params.position)
                val selection = params.range?.let { range ->
                    val start = hostDocument.offsetByPosition(range.start)
                    TextRange(start, max(hostDocument.offsetByPosition(range.end), start))
                } ?: TextRange(caret, caret)

                val scope = injectedScope(hostFile, caret, selection)
                    ?: Scope(hostFile, hostDocument, caret, selection).takeIf { it.handlers.isNotEmpty() }
                    ?: return@readAction null
                val expressions = ShowExpressionTypeHandler.getExpressions(scope.file, scope.selection, scope.document, scope.handlers)
                expressions.mapNotNull { (element, provider) ->
                    @Suppress("UNCHECKED_CAST")
                    provider as ExpressionTypeProvider<PsiElement>
                    val hostRange = if (scope.file === hostFile) element.textRange else element.injectedToHostRange()?.second
                    if (hostRange == null) return@mapNotNull null
                    val hint = if (params.advanced && provider.hasAdvancedInformation()) provider.getAdvancedInformationHint(element)
                    else provider.getInformationHint(element)
                    ExpressionType(hostRange.toLspRange(hostDocument), hint, provider.hasAdvancedInformation())
                }
            }
        }
    }

    /** The file the providers read, with its [document], the caret [offset] and the [selection] in its coordinates. */
    private class Scope(val file: PsiFile, val document: Document, offset: Int, val selection: TextRange) {
        val handlers: Set<ExpressionTypeProvider<*>> =
            ShowExpressionTypeHandler.getHandlers(file.project, PsiUtilCore.getLanguageAtOffset(file, offset), file.viewProvider.baseLanguage)
    }

    /**
     * The injected file at [caret] when both ends of [selection] lie in its fragments and its language has a provider. The ends
     * may lie in different fragments: an injected expression may span several, and its host range comes back as the selection.
     */
    private fun injectedScope(hostFile: PsiFile, caret: Int, selection: TextRange): Scope? {
        val (injected, injectedCaret) = lsFindInjectedFileAt(hostFile, caret) ?: return null
        val window = injected.documentWindow
        fun inFragment(offset: Int) = window.hostRanges.any { it.startOffset <= offset && offset <= it.endOffset }
        if (!inFragment(selection.startOffset) || !inFragment(selection.endOffset)) return null
        val injectedSelection = TextRange(window.hostToInjected(selection.startOffset), window.hostToInjected(selection.endOffset))
        return Scope(injected.psiFile, window, injectedCaret, injectedSelection).takeIf { it.handlers.isNotEmpty() }
    }
}
