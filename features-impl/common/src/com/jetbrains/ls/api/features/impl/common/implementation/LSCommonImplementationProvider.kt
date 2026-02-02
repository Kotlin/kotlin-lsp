// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.implementation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.features.implementation.LSImplementationProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ImplementationParams
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonImplementationProvider(
    override val supportedLanguages: Set<LSLanguage>
) : LSImplementationProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideImplementations(params: ImplementationParams): Flow<Location> = flow {
        server.withAnalysisContext(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val offset = document.offsetByPosition(params.position)

                val editor = ImaginaryEditor(project, document).apply {
                    caretModel.primaryCaret.moveToOffset(offset)
                }

                // Exclude LOOKUP_ITEM_ACCEPTED because LSP has no completion popups
                val flags = ImplementationSearcher.getFlags() and TargetElementUtil.LOOKUP_ITEM_ACCEPTED.inv()
                val targetElementUtil = TargetElementUtil.getInstance()

                val source = targetElementUtil.findTargetElement(editor, flags, offset) ?: return@readAction emptyList()
                val referenceAtCaret = TargetElementUtil.findReference(editor, offset)
                val rawCandidates = ImplementationSearcher().searchImplementations(editor, source, offset) ?: emptyArray()
                val filtered = rawCandidates.filter { candidate ->
                    targetElementUtil.acceptImplementationForReference(referenceAtCaret, candidate)
                }

                filtered.mapNotNull { psiElement -> psiElement.toLocation() }
            }
        }.forEach { location -> emit(location) }
    }
}

@RequiresReadLock
private fun PsiElement.toLocation(): Location? {
    val virtualFile = this.containingFile?.virtualFile ?: return null
    val document = virtualFile.findDocument() ?: return null
    val targetRange = when (this) {
        is PsiNameIdentifierOwner -> {
            // Narrow the location to the identifier of the method/class rather than the whole body
            this.nameIdentifier?.textRange ?: this.textRange
        }

        else -> this.textRange
    } ?: return null

    return Location(
        uri = DocumentUri(virtualFile.uri),
        range = targetRange.toLspRange(document),
    )
}
