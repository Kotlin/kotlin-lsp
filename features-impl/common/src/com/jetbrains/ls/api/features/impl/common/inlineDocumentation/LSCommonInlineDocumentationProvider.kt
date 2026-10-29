// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.inlineDocumentation

import com.intellij.lang.documentation.impl.documentationTargets
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.backend.documentation.AsyncDocumentation
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.InlineDocumentationProvider
import com.intellij.platform.backend.documentation.impl.resolveLinkToTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.util.AsyncSupplier
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.getLspLocationForDefinition
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.inlineDocumentation.DocLinkResolution
import com.jetbrains.ls.api.features.inlineDocumentation.InlineDocumentationItem
import com.jetbrains.ls.api.features.inlineDocumentation.InlineDocumentationParams
import com.jetbrains.ls.api.features.inlineDocumentation.LSInlineDocumentationProvider
import com.jetbrains.ls.api.features.inlineDocumentation.ResolveDocLinkParams
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import kotlin.math.max

/**
 * Serves every language through the `platform.backend.documentation.inlineDocumentationProvider` and `linkHandler`
 * extensions the server ships, as the IDE renders doc comments in reader mode. Ranges are in the host document.
 *
 * [inlineDocumentation] answers `null` when the file is unknown or the server has no inline documentation provider, and
 * otherwise the items of all providers, which is empty for a file without doc comments or of a language no provider serves.
 */
object LSCommonInlineDocumentationProvider : LSInlineDocumentationProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun inlineDocumentation(params: InlineDocumentationParams): List<InlineDocumentationItem>? {
        return server.withAnalysisContext {
            readAction {
                val providers = InlineDocumentationProvider.EP_NAME.extensionList
                if (providers.isEmpty()) return@readAction null
                val (psiFile, document) = findFile(params.textDocument) ?: return@readAction null
                providers.flatMap { it.inlineDocumentationItems(psiFile) }.sortedBy { it.documentationRange.startOffset }.map { item ->
                    InlineDocumentationItem(
                        range = item.documentationRange.toLspRange(document),
                        ownerRange = item.documentationOwnerRange?.toLspRange(document),
                        html = if (params.renderText) item.renderText() else null,
                    )
                }
            }
        }
    }

    /**
     * The context target is the owner of the doc comment at [ResolveDocLinkParams.range], else the documentation target at
     * its start (a declaration location from a previous resolution). The link resolves against it as in the IDE popup.
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun resolveDocLink(params: ResolveDocLinkParams): DocLinkResolution? {
        return server.withAnalysisContext {
            val context = readAction { contextTarget(params.textDocument, params.range)?.createPointer() } ?: return@withAnalysisContext null
            val resolved = resolveLinkToTarget(context, params.url) ?: return@withAnalysisContext null
            var asyncHtml: AsyncSupplier<DocumentationResult.Documentation?>? = null
            val resolution = readAction {
                val target = resolved.dereference() ?: return@readAction null
                val html = when (val documentation = target.computeDocumentation()) {
                    is DocumentationResult.Documentation -> documentation.html()
                    is AsyncDocumentation -> null.also { asyncHtml = documentation.supplier }
                    null -> null
                }
                // not computePresentation(): its module text needs the UI renderers the server lacks
                val element = target.navigatable as? PsiElement
                DocLinkResolution(html = html, location = element?.getLspLocationForDefinition(), name = (element as? PsiNamedElement)?.name)
            } ?: return@withAnalysisContext null
            // an async supplier may compute outside the read action, as the IDE popup does
            asyncHtml?.let { resolution.copy(html = it()?.html()) } ?: resolution
        }
    }

    context(analysisContext: LSAnalysisContext)
    private fun contextTarget(textDocument: TextDocumentIdentifier, range: Range): DocumentationTarget? {
        val (psiFile, document) = findFile(textDocument) ?: return null
        val start = document.offsetByPosition(range.start)
        val textRange = TextRange(start, max(document.offsetByPosition(range.end), start))
        val inline = InlineDocumentationProvider.EP_NAME.extensionList.firstNotNullOfOrNull { it.findInlineDocumentation(psiFile, textRange) }
        return inline?.ownerTarget ?: documentationTargets(psiFile, start, false).firstOrNull()
    }

    context(analysisContext: LSAnalysisContext)
    private fun findFile(textDocument: TextDocumentIdentifier): Pair<PsiFile, Document>? {
        val virtualFile = textDocument.findVirtualFile() ?: return null
        val psiFile = virtualFile.findPsiFile(project) ?: return null
        val document = virtualFile.findDocument() ?: return null
        return psiFile to document
    }

    private fun DocumentationResult.Documentation.html(): String? = (this as? DocumentationData)?.html
}
