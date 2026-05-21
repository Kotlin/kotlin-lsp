// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.model.psi.PsiSymbolService
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.rename.LSRenameProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.RenameParams
import com.jetbrains.lsp.protocol.RenameRequestType
import com.jetbrains.lsp.protocol.WorkspaceEdit

abstract class LSRenameProviderBase(
    override val supportedLanguages: Set<LSLanguage>,
) : LSRenameProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun rename(params: RenameParams): WorkspaceEdit {
        val changes = server.withWriteAnalysisContext {
            val context = readAction {
                val virtualFile = params.findVirtualFile() ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val targets = extractTargets(psiFile, offset)
                val target = targets.firstOrNull(::isAllowedToRename)
                    ?: throwLspError(RenameRequestType, "This element cannot be renamed", Unit, ErrorCodes.InvalidParams, null)

                RenameContext(target, params.newName, DiffGranularity.CHARACTER, null)
            } ?: return@withWriteAnalysisContext emptyList()

            doRename(context)
        }

        return WorkspaceEdit(documentChanges = changes)
    }

    open fun extractTargets(psiFile: PsiFile, offset: Int): List<PsiElement> {
        val psiSymbolService = PsiSymbolService.getInstance()
        return targetSymbols(psiFile, offset).mapNotNull { psiSymbolService.extractElementFromSymbol(it) }
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun renameFile(params: FileRename): WorkspaceEdit? {
        val edits = server.withWriteAnalysisContext {
            val context = readAction {
                // check that a file was already renamed on the previous step
                if (params.newUri.findVirtualFile() != null) return@readAction null

                val nameChange = computeNameChange(params.oldUri, params.newUri, false) ?: return@readAction null
                val virtualFile = params.oldUri.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val target = getTargetClass(psiFile, nameChange.oldName) ?: return@readAction null
                RenameContext(target, nameChange.newName, DiffGranularity.WORD, params.oldUri)
            } ?: return@withWriteAnalysisContext null

            doRename(context)
        }

        return WorkspaceEdit(documentChanges = edits)
    }

    protected abstract fun getTargetClass(psiFile: PsiFile, name: String): PsiElement?

    protected open fun isAllowedToRename(target: PsiElement): Boolean = true

}
