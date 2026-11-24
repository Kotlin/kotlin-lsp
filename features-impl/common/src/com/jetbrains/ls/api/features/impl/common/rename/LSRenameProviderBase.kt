// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.rename

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiDirectoryContainer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.modplan.ModPlanCommandAction
import com.intellij.refactoring.modplan.ModPlanOptionValues
import com.intellij.refactoring.modplan.rename.ModPlanRenameOptions
import com.intellij.refactoring.modplan.rename.ModPlanRenameRefactoring
import com.intellij.refactoring.rename.HeadlessRenameProcessor
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.processors.LSRenameCustomizer
import com.jetbrains.ls.api.core.processors.LSRenameProcessor
import com.jetbrains.ls.api.core.processors.RenameContext
import com.jetbrains.ls.api.core.processors.prepareRenameProcessor
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withWriteAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.impl.common.processors.doRefactoring
import com.jetbrains.ls.api.features.impl.common.processors.failRenameIfRefused
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.rename.LSRenameProvider
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.DiffGranularity
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.FileRename
import com.jetbrains.lsp.protocol.PrepareRenameParams
import com.jetbrains.lsp.protocol.PrepareRenameRequestType
import com.jetbrains.lsp.protocol.PrepareRenameResult
import com.jetbrains.lsp.protocol.RenameParams
import com.jetbrains.lsp.protocol.WorkspaceEdit

abstract class LSRenameProviderBase(
    override val supportedLanguages: Set<LSLanguage>,
) : LSRenameProvider {
    /** What this language changes in the rename. */
    protected open val renameCustomizer: LSRenameCustomizer get() = LSRenameCustomizer.DEFAULT

    /** The unit of the diff that reports the text edits of a rename. */
    protected open val renameDiffGranularity: DiffGranularity get() = DiffGranularity.CHARACTER

    /**
     * Whether [rename] runs the ModPlan rename as a ModCommand, see [renameWithModCommand]. False renames with [LSRenameProcessor], which
     * asks no question.
     */
    protected open val renamesWithModCommand: Boolean get() = false

    /** Whether [renameFile] renames the related elements of the automatic renamers of the settings, see [RenameContext.runsAutomaticRenamers]. */
    protected open val fileRenameRunsAutomaticRenamers: Boolean get() = true

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun rename(params: RenameParams): WorkspaceEdit {
        if (renamesWithModCommand && server.config.clientSupportsEditOptions && renameWithModCommand(params)) {
            return WorkspaceEdit(documentChanges = emptyList())
        }
        val changes = server.withWriteAnalysisContext {
            val processor = context(project) { prepareRenameProcessor(params, renameCustomizer) }
                ?: return@withWriteAnalysisContext emptyList()
            doRefactoring(processor, renameDiffGranularity, null, showNotificationWithError = false)
                .also { (processor as? LSRenameProcessor)?.let { failRenameIfRefused(it, showNotificationWithError = false) } }
        }

        return WorkspaceEdit(documentChanges = renameCustomizer.clientChanges(changes))
    }

    /**
     * Renames the target of [params] with the [ModPlanCommandAction] of [ModPlanRenameRefactoring], as safe delete does. The client
     * answers the questions of the rename in the forms of `intellij/editOptions`, and confirms the conflicts. The server sends the edits
     * with `workspace/applyEdit` while the rename request waits, so the response of the request holds no edit. The plan runs in the write
     * analysis context, as in [LSRenameProcessor]: in the read one, the search finds no usage.
     *
     * [rename] calls it only for a client that declares `editOptions`. A client without it, such as idecli, renames with
     * [LSRenameProcessor]: it gets the edits in the response, and a refusal or a conflict as an error.
     *
     * @return false if the ModPlan engine does not support the target, or the client cannot show its command, so that
     * [LSRenameProcessor] renames it
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    private suspend fun renameWithModCommand(params: RenameParams): Boolean =
        server.withWriteAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            val command = readAction { renameCommand(params) } ?: return@withWriteAnalysisContextAndFileSettings false
            executeCommand(command, lspClient)
            true
        }

    /** The command of the rename of [renameWithModCommand], or null if it falls back to [LSRenameProcessor]. */
    context(server: LSServer, _: LSAnalysisContext)
    private fun renameCommand(params: RenameParams): ModCommandData? {
        val virtualFile = params.findVirtualFile() ?: return null
        val document = virtualFile.findDocument() ?: return null
        val psiFile = virtualFile.findPsiFile(project) ?: return null
        val offset = document.offsetByPosition(params.position)
        val target = renameCustomizer.extractTargets(psiFile, offset).firstOrNull() ?: return null
        // A package of several directories renames one directory of it in prepareRenameProcessor.
        if (target is PsiDirectoryContainer) return null
        val refactoring = ModPlanRenameRefactoring.getInstance()
        val request = ModPlanRenameRefactoring.request(
            project, target, params.newName, substitute = true,
            ModPlanRenameOptions.withRenamersOfSettings(ModPlanOptionValues.EMPTY).with(ModPlanRenameOptions.SKIP_READ_ONLY_USAGES, true))
        @Suppress("DialogTitleCapitalization")
        val action = ModPlanCommandAction(refactoring, request, target, refactoring.title(), false)
        val actionContext = ActionContext(project, psiFile, offset, TextRange.create(offset, offset), null)
        if (action.getPresentation(actionContext) == null) return null
        return ModCommandData.from(action.perform(actionContext), actionContext, server)
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun prepareRename(params: PrepareRenameParams): PrepareRenameResult? {
        return server.withAnalysisContext {
            readAction {
                val virtualFile = params.findVirtualFile() ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val offset = document.offsetByPosition(params.position)
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val target = renameCustomizer.extractTargets(psiFile, offset).firstOrNull()
                if (target == null || !canRename(target)) {
                    throwLspError(PrepareRenameRequestType, "This element cannot be renamed", Unit, ErrorCodes.InvalidParams, null)
                }
                val adjustedOffset = TargetElementUtil.adjustOffset(psiFile, document, offset)
                // the client types the new name over the returned range, so only an identifier position can host it
                val text = document.charsSequence
                if (adjustedOffset >= text.length || !Character.isJavaIdentifierPart(text[adjustedOffset])) {
                    throwLspError(PrepareRenameRequestType, "Rename is not available at this position", Unit, ErrorCodes.InvalidParams, null)
                }
                val leaf = PsiUtilCore.getElementAtOffset(psiFile, adjustedOffset)
                if (leaf is PsiFile) return@readAction PrepareRenameResult.Default(true)
                val range = renameRangeAt(psiFile, adjustedOffset) ?: leaf.textRange
                if (range == null || range.isEmpty) return@readAction PrepareRenameResult.Default(true)
                PrepareRenameResult.Labeled(range.toLspRange(document), placeholderFor(range.substring(text.toString())))
            }
        }
    }

    /**
     * Whether [target] can be renamed: the ModPlan engine supports it, or [HeadlessRenameProcessor] renames it, as [LSRenameProcessor]
     * falls back to it.
     */
    private fun canRename(target: PsiElement): Boolean {
        val request = ModPlanRenameRefactoring.request(target.project, target, "", substitute = true, ModPlanOptionValues.EMPTY)
        return ModPlanRenameRefactoring.getInstance().isSupported(request) || HeadlessRenameProcessor.canRename(target)
    }

    protected open fun placeholderFor(identifierText: String): String = identifierText

    /**
     * The range the client edits when the name under the caret is a part of a bigger token, such as a
     * segment of a path in a string. Null takes the token under the caret.
     */
    protected open fun renameRangeAt(psiFile: PsiFile, offset: Int): TextRange? = null

    context(server: LSServer, handlerContext: LspHandlerContext)
    override suspend fun renameFile(params: FileRename): WorkspaceEdit? {
        val edits = server.withWriteAnalysisContext {
            val processor = readAction {
                // check that a file was already renamed on the previous step
                if (params.newUri.findVirtualFile() != null) return@readAction null

                val nameChange = computeNameChange(params.oldUri, params.newUri, false) ?: return@readAction null
                val virtualFile = params.oldUri.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val target = getTargetClass(psiFile, nameChange.oldName.fileName) ?: psiFile
                val newName = if (target is PsiFile) nameChange.newName.fullName() else nameChange.newName.fileName
                LSRenameProcessor.create(RenameContext(target, newName, runsAutomaticRenamers = fileRenameRunsAutomaticRenamers))
            } ?: return@withWriteAnalysisContext null

            doRefactoring(processor, DiffGranularity.WORD, params.oldUri, true)
                .also { failRenameIfRefused(processor, showNotificationWithError = true) }
        }

        return WorkspaceEdit(documentChanges = edits)
    }

    protected abstract fun getTargetClass(psiFile: PsiFile, name: String): PsiElement?
}
