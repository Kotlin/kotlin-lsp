// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.intentions

import com.intellij.codeInsight.intention.CommonIntentionAction
import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.descendantsOfType
import com.intellij.psi.util.startOffset
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.modcommands.LSApplyFixCommandDescriptorProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinApplicableModCommandAction
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.AddNameToArgumentIntention
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.AddNamesToCallArgumentsIntention
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.AddNamesToFollowingArgumentsIntention
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.MovePropertyToConstructorIntention
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.SpecifyTypeExplicitlyIntention
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinIntentionCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> get() = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    private fun createActions(): List<KotlinApplicableModCommandAction<*, *>> {
        return listOf(
            AddNamesToCallArgumentsIntention(),
            AddNamesToFollowingArgumentsIntention(),
            AddNameToArgumentIntention(),

            MovePropertyToConstructorIntention(),

            SpecifyTypeExplicitlyIntention(
                useTemplate = false // templates have to be disabled until LSP-478 is fixed
            ),
        )
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val uri = params.textDocument.uri.uri
        server.withAnalysisContext {
            readAction {
                val virtualFile = uri.findVirtualFile() ?: return@readAction emptyList()
                val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val actions = createActions()
                analyze(ktFile) {
                    val result = mutableListOf<CodeAction>()
                    for (ktElement in ktFile.descendantsOfType<KtElement>()) {
                        if (!params.range.intersects(ktElement.textRange.toLspRange(document))) continue
                        val actionContext = createActionContext(ktFile, ktElement)
                        for (action in actions) {
                            val codeAction = runCatching {
                                toCodeAction(action, actionContext, ktElement)
                            }.getOrHandleException { LOG.debug(it) } ?: continue
                            result += codeAction
                        }
                    }
                    result
                }
            }
        }.forEach { emit(it) }
    }

    context(analysisContext: LSAnalysisContext)
    private fun createActionContext(ktFile: KtFile, element: PsiElement) = ActionContext(
        project,
        ktFile,
        element.startOffset,
        TextRange(element.startOffset, element.startOffset), // empty selection
        element,
    )

    private fun toCodeAction(
        action: KotlinApplicableModCommandAction<*, *>,
        actionContext: ActionContext,
        child: KtElement
    ): CodeAction? {
        val modCodeAction = (action as? CommonIntentionAction)?.asModCommandAction()
        if (modCodeAction == null) {
            LOG.warn("Cannot convert $action to ModCommandAction")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        if (!(action as KotlinApplicableModCommandAction<KtElement, *>).isApplicableByPsi(child)) return null
        val presentation = action.getPresentation(actionContext) ?: return null
        val ktPsiFile = child.containingKtFile
        analyze(ktPsiFile) {
            val actionContext = run {
                val offset = child.startOffset
                val selection = TextRange(offset, offset) // empty selection
                ActionContext(child.project, ktPsiFile, offset, selection, null)
            }
            val modCommand = modCodeAction.perform(actionContext)
            val modCommandData = ModCommandData.from(modCommand) ?: return null
            return CodeAction(
                title = presentation.name,
                kind = CodeActionKind.QuickFix,
                diagnostics = null,
                command = Command(
                    title = LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
                    command = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
                    arguments = listOf(
                        LSP.json.encodeToJsonElement(modCommandData),
                    ),
                ),
            )
        }
    }
}

private val LOG = fileLogger()

