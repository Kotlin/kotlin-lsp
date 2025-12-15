// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.LSApplyFixCommandDescriptorProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

private val LOG = fileLogger()

class LSCommonIntentionFixesCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val blacklist: Blacklist = Blacklist(),
) : LSCodeActionProvider {

    override val providesOnlyKinds: Set<CodeActionKind> get() = setOf(codeActionKind)

    context(_: LSServer, _: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        if (!params.textDocument.isSource()) return@flow

        withAnalysisContext(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val actionContext = run {
                    val offset = params.range.toTextRange(document).startOffset
                    val selection = TextRange(offset, offset) // empty selection
                    ActionContext(project, psiFile, offset, selection, null)
                }

                val codeActions = IntentionManager.getInstance()
                    .getAvailableIntentions(languageIds)
                    .asSequence()
                    .mapNotNull { intentionAction -> intentionAction.asModCommandAction() }
                    .filterNot { modCommandAction -> blacklist.containsImplementation(modCommandAction.javaClass.name) }
                    .mapNotNull { modCommandAction ->
                        val presentation = try {
                            modCommandAction.getPresentation(actionContext)
                        } catch (e: Throwable) {
                            // If some ModCommand is not available, calling getPresentation() in such case should return null, not throw.
                            // We want to know if getPresentation() throws, since it may point to missing registration of some extensions in the LSP.
                            LOG.warn("Failed to presentation from mod command action $modCommandAction", e)
                            return@mapNotNull null
                        }
                        if (presentation == null) {
                            // This case is equivalent to getting false from IntentionAction#isAvailable
                            return@mapNotNull null
                        }
                        return@mapNotNull Pair(modCommandAction, presentation)
                    }
                    .mapNotNull { (modCommandAction, presentation) ->
                        val modCommand = try {
                            modCommandAction.perform(actionContext)
                        } catch (e: Throwable) {
                            LOG.warn("Failed to perform mod command action $modCommandAction", e)
                            return@mapNotNull null
                        }
                        val modCommandData = ModCommandData.from(modCommand) ?: return@mapNotNull null

                        CodeAction(
                            title = presentation.name,
                            kind = codeActionKind,
                            command = Command(
                                title = LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
                                command = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
                                arguments = listOf(
                                    LSP.json.encodeToJsonElement(modCommandData),
                                ),
                            ),
                        )
                    }
                    .toList()

                return@readAction codeActions
            }
        }.forEach { codeAction -> emit(codeAction) }
    }

    private val codeActionKind: CodeActionKind = CodeActionKind.Refactor

    private val languageIds: List<String> get() = supportedLanguages.map { language -> language.intellijLanguage.id }
}
