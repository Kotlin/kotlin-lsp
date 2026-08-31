// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.extract

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Base class for extract refactorings (variable, field, constant, ...) implemented as a [ModCommandAction].
 *
 * [toModCommandFixes] offers the action of [createAction] to the client, so everything the refactoring needs is handled
 * by the shared ModCommand plumbing: the action is performed only once the user picks the code action, the edits of the
 * [ModCommand] it produces travel as [ModCommandData][com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData] applied by
 * [LSApplyFixCommandDescriptorProvider][com.jetbrains.ls.api.features.impl.common.modcommands.LSApplyFixCommandDescriptorProvider],
 * an inline rename becomes [ModStartRename][com.intellij.modcommand.ModStartRename], and a [ModChooseAction] of variants
 * is either shown by the client as a menu or expanded into one code action per variant, depending on whether the client
 * declares `intellijExtensions`.
 *
 * This suits refactorings local to a single file: the whole command is computed on a copy of the file, so the only
 * interaction left is picking a choice. One needing its own client/server protocol uses [LSExtractMemberProviderBase]
 * instead.
 */
abstract class LSExtractModCommandProviderBase : LSCodeActionProvider {
    /** The kind of the produced code actions, which is also what a client binds an "extract" hot key to. */
    protected abstract val extractActionKind: CodeActionKind

    /**
     * The action performing the extraction at [context], or `null` if there is nothing to extract there, in which case
     * no code action is produced at all. Its presentation name becomes the title of the code action.
     *
     * Called once per request, in the read action the code actions are computed in, so an implementation may analyze the
     * context here and build an action out of what it found instead of analyzing it again in
     * [ModCommandAction.getPresentation] and [ModCommandAction.perform].
     */
    protected abstract fun createAction(context: ActionContext): ModCommandAction?

    override val providesOnlyKinds: Set<CodeActionKind> get() = setOf(extractActionKind)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val fixes = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val selection = params.range.toTextRange(document)
                val actionContext = ActionContext(project, psiFile, selection.startOffset, selection, null)
                val action = createAction(actionContext) ?: return@readAction emptyList()
                action.toModCommandFixes(actionContext)
            }
        }
        for ((name, data) in fixes) {
            emit(applyFixCodeAction(name, extractActionKind, data))
        }
    }
}
