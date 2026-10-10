// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.safeDelete

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandAction
import com.intellij.model.SymbolPointer
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.modplan.ModPlanCommandAction
import com.intellij.refactoring.modplan.ModPlanOptionValues
import com.intellij.refactoring.modplan.ModPlanRequest
import com.intellij.refactoring.modplan.safeDelete.ModPlanSafeDeleteRefactoring
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.processors.LSRenameCustomizer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.ModCommandFix
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.registerLazyFixes
import com.jetbrains.ls.api.features.impl.common.modcommands.toLazyFix
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The kinds of the code actions of the safe delete. */
object SafeDeleteActionKind {
    /** Deletes the symbol at the caret if no usage needs it, and shows the usages that do before it goes on. */
    val RefactorRewriteSafeDelete: CodeActionKind = CodeActionKind("refactor.rewrite.safeDelete")
}

/**
 * The safe delete of the symbol at the caret, as a [ModCommandAction] of the languages in [supportedLanguages]. The action is offered
 * without a search, as a lazy fix for every client: the usages are searched only when the client runs it. A client that declares
 * `intellijExtensions` is then asked about the usages that still need the symbol and about the non-code usages with
 * `intellij/showConflicts`; for one that does not, the deletion goes on, and a message lists them. The questions are not asked: they
 * take the defaults of the options, not the values that the settings remember, see [ModPlanCommandAction].
 */
class LSCommonSafeDeleteProvider(override val supportedLanguages: Set<LSLanguage>) : LSCodeActionProvider {
    override val providesOnlyKinds: Set<CodeActionKind> get() = setOf(SafeDeleteActionKind.RefactorRewriteSafeDelete)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val fixes = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val selection = params.range.toTextRange(document)
                val target = LSRenameCustomizer.DEFAULT.extractTargets(psiFile, selection.startOffset).firstOrNull() ?: return@readAction emptyList()
                val request = ModPlanRequest(project, listOf(SymbolPointer.of(target)), ModPlanOptionValues.EMPTY)
                @Suppress("DialogTitleCapitalization")
                val action = ModPlanCommandAction(ModPlanSafeDeleteRefactoring.getInstance(), request, target,
                                                    RefactoringBundle.message("safe.delete.title"), false)
                val actionContext = ActionContext(project, psiFile, selection.startOffset, selection, null)
                val fix = action.toLazyFix(actionContext) ?: return@readAction emptyList()
                // Not through toModCommandFixes: for a client without lazyIntentions it would perform the fix, which is the whole search.
                registerLazyFixes(server, virtualFile, listOf(fix)).map { ModCommandFix(fix.name, it) }
            }
        }
        for ((name, data) in fixes) {
            emit(applyFixCodeAction(name, SafeDeleteActionKind.RefactorRewriteSafeDelete, data, emptyMap()))
        }
    }
}
