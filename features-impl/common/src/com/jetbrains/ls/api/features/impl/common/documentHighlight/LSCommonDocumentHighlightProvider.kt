// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.documentHighlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.TargetKind
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.getTargetsAtPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.documentHighlight.LSDocumentHighlightProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentHighlight
import com.jetbrains.lsp.protocol.DocumentHighlightKind
import com.jetbrains.lsp.protocol.DocumentHighlightParams

class LSCommonDocumentHighlightProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val targetKinds: Set<TargetKind>,
) : LSDocumentHighlightProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun getDocumentHighlights(params: DocumentHighlightParams): List<DocumentHighlight>? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val target = psiFile.getTargetsAtPosition(params.position, targetKinds).firstOrNull() ?: return@readAction null
                val detector = ReadWriteAccessDetector.findDetector(target)
                val handler = FindUsagesManager(project).getFindUsagesHandler(target, true/*forbid showing dialogs*/) ?: return@readAction null

                val kindsByRange = LinkedHashMap<TextRange, DocumentHighlightKind>()
                declarationNameRange(target, psiFile)?.let { range ->
                    kindsByRange[range] = when {
                        detector?.isDeclarationWriteAccess(target) == true -> DocumentHighlightKind.Write
                        else -> DocumentHighlightKind.Text
                    }
                }
                handler.findReferencesToHighlight(target, LocalSearchScope(psiFile)).forEach { reference ->
                    val kind = referenceKind(detector, target, reference)
                    val ranges = ArrayList<TextRange>()
                    HighlightUsagesHandler.collectHighlightRanges(reference, ranges)
                    ranges.forEach { kindsByRange.putIfAbsent(it, kind) }
                }

                val document = psiFile.fileDocument
                kindsByRange.entries
                    .sortedBy { it.key.startOffset }
                    .map { DocumentHighlight(it.key.toLspRange(document), it.value) }
            }
        }
    }

    private fun declarationNameRange(target: PsiElement, psiFile: PsiFile): TextRange? {
        val nameIdentifier = (target as? PsiNameIdentifierOwner)?.nameIdentifier ?: return null
        if (nameIdentifier.containingFile != psiFile) return null
        return nameIdentifier.textRange?.takeIf { !it.isEmpty }
    }

    private fun referenceKind(detector: ReadWriteAccessDetector?, target: PsiElement, reference: PsiReference): DocumentHighlightKind {
        return when (detector?.getReferenceAccess(target, reference)) {
            null -> DocumentHighlightKind.Text
            ReadWriteAccessDetector.Access.Read -> DocumentHighlightKind.Read
            ReadWriteAccessDetector.Access.Write, ReadWriteAccessDetector.Access.ReadWrite -> DocumentHighlightKind.Write
        }
    }
}
