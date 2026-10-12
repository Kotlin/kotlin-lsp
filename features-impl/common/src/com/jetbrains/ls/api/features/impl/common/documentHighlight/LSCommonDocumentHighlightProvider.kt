// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.documentHighlight

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.ReferenceRange
import com.intellij.psi.search.LocalSearchScope
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSInjectedFile
import com.jetbrains.ls.api.core.features.hostRanges
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
                val declarationKind = when {
                    detector?.isDeclarationWriteAccess(target) == true -> DocumentHighlightKind.Write
                    else -> DocumentHighlightKind.Text
                }
                declarationNameHostRanges(target, psiFile).forEach { kindsByRange[it] = declarationKind }
                // The host file scope also covers its injections, so a target in the host or in any injection gets every usage.
                handler.findReferencesToHighlight(target, LocalSearchScope(psiFile)).forEach { reference ->
                    val kind = referenceKind(detector, target, reference)
                    ReferenceRange.getAbsoluteRanges(reference).forEach { range ->
                        hostRanges(reference.element, range, psiFile).forEach { kindsByRange.putIfAbsent(it, kind) }
                    }
                }

                val document = psiFile.fileDocument
                kindsByRange.entries
                    .sortedBy { it.key.startOffset }
                    .map { DocumentHighlight(it.key.toLspRange(document), it.value) }
            }
        }
    }

    /** The host ranges of the declaration name of [target] when it lies in [hostFile] or in one of its injections. */
    private fun declarationNameHostRanges(target: PsiElement, hostFile: PsiFile): List<TextRange> {
        val nameIdentifier = (target as? PsiNameIdentifierOwner)?.nameIdentifier ?: return emptyList()
        return hostRanges(nameIdentifier, nameIdentifier.textRange ?: return emptyList(), hostFile)
    }

    /**
     * The host ranges of [range] in the file of [element] when that is [hostFile] or one of its injections:
     * one range per editable fragment, so the host text between the fragments (quotes, `+`) stays out.
     */
    private fun hostRanges(element: PsiElement, range: TextRange, hostFile: PsiFile): List<TextRange> {
        if (range.isEmpty) return emptyList()
        val file = element.containingFile ?: return emptyList()
        if (file == hostFile) return listOf(range)
        val injectedLanguageManager = InjectedLanguageManager.getInstance(hostFile.project)
        if (!injectedLanguageManager.isInjectedFragment(file) || injectedLanguageManager.getTopLevelFile(file) != hostFile) return emptyList()
        val window = PsiDocumentManager.getInstance(hostFile.project).getCachedDocument(file) as? DocumentWindow ?: return emptyList()
        return LSInjectedFile(file, window).hostRanges(range)
    }

    private fun referenceKind(detector: ReadWriteAccessDetector?, target: PsiElement, reference: PsiReference): DocumentHighlightKind {
        return when (detector?.getReferenceAccess(target, reference)) {
            null -> DocumentHighlightKind.Text
            ReadWriteAccessDetector.Access.Read -> DocumentHighlightKind.Read
            ReadWriteAccessDetector.Access.Write, ReadWriteAccessDetector.Access.ReadWrite -> DocumentHighlightKind.Write
        }
    }
}
