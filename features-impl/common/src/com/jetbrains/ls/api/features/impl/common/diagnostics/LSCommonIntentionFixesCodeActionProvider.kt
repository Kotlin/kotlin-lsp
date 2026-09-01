// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parents
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.lsGetLocalInspections
import com.jetbrains.ls.api.core.features.lsGetSharedLocalInspectionsFromGlobalTools
import com.jetbrains.ls.api.core.features.lsIsSuppressed
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val LOG = logger<LSCommonIntentionFixesCodeActionProvider>()

/**
 * @param converter an optional function that adapts ModCommand-based intention actions to LSP
 * (e.g., for specific actions, it may modify it somehow to avoid using unsupported LSP stuff).
 */
class LSCommonIntentionFixesCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val intentionBlacklist: Blacklist = Blacklist(),
    private val quickFixBlacklist: Blacklist = Blacklist(),
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
    private val converter: (ModCommandAction) -> ModCommandAction = {it}
) : LSCodeActionProvider {
    private val lsInspectionManager = LSInspectionManager(quickFixBlacklist)

    override val providesOnlyKinds: Set<CodeActionKind> get() = setOf(codeActionKind)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        if (!params.textDocument.isSource()) return@flow

        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val offset = params.range.toTextRange(document).startOffset
                val psiElement = psiFile.findElementAt(offset)

                // TODO(bartekpacia): centralize common logging so it's not repeated N times across all LS*Providers
                LOG.debug("request textDocument/diagnostic for ${virtualFile.name}")

                val codeActions =
                    (infoInspections(psiFile, offset, psiElement) + intentions(psiFile, offset))
                    .toList()

                return@readAction codeActions
            }
        }.forEach { codeAction -> emit(codeAction) }
    }

    context(server: LSServer)
    private fun infoInspections(psiFile: PsiFile, offset: Int, psiElement: PsiElement?): Sequence<CodeAction> {
        if (psiElement == null) return emptySequence()
        val project = psiFile.project
        val inspectionManager = InspectionManagerEx(project)
        val problemsHolder = ProblemsHolder(inspectionManager, psiFile, true)
        val infoInspections = lsGetLocalInspections(psiFile, inspectionProfilePatcher, true) +
                lsGetSharedLocalInspectionsFromGlobalTools(psiFile.language, inspectionProfilePatcher, true)
        val normalInspections = lsGetLocalInspections(psiFile, inspectionProfilePatcher, false) +
                lsGetSharedLocalInspectionsFromGlobalTools(psiFile.language, inspectionProfilePatcher,false)
        val fileRange = psiFile.textRange
        val session = LocalInspectionToolSession(psiFile, fileRange, fileRange, null)
        val result = mutableListOf<CodeAction>()
        for ((isInfo, list) in listOf(false to normalInspections, true to infoInspections)) {
            for (localInspection in list) {
                val visitor = localInspection.buildVisitor(problemsHolder, true, session)
                psiElement.parents(true).forEach { element ->
                    runCatching {
                        element.accept(visitor)
                    }.getOrHandleException {
                        LOG.warn(it)
                    }
                    for (descriptor in problemsHolder.results) {
                        if ((isInfo || descriptor.highlightType == ProblemHighlightType.INFORMATION) &&
                            !lsIsSuppressed(localInspection, descriptor)
                        ) {
                            val elementRange = (descriptor.psiElement ?: descriptor.startElement)?.textRange ?: continue
                            val range = descriptor.textRangeInElement?.shiftRight(elementRange.startOffset) 
                                ?: elementRange
                            if (!range.containsOffset(offset)) continue
                            for ((name, modCommandData) in lsInspectionManager.createDiagnosticData(descriptor).fixes) {
                                result.add(applyFixCodeAction(name, codeActionKind, modCommandData))
                            }
                        }
                    }
                    problemsHolder.clearResults()
                }
            }
        }
        return result.asSequence()
    }

    context(server: LSServer)
    private fun intentions(psiFile: PsiFile, offset: Int): Sequence<CodeAction> {
        val actionContext = run {
            val selection = TextRange(offset, offset) // empty selection
            ActionContext(psiFile.project, psiFile, offset, selection, null)
        }

        return IntentionManager.getInstance()
            .getAvailableIntentions(languageIds)
            .asSequence()
            .mapNotNull { intentionAction -> intentionAction.asModCommandAction() }
            .filterNot { modCommandAction ->
                val actionClass = modCommandAction.javaClass.name
                val blacklistEntry = quickFixBlacklist.getImplementationBlacklistEntry(actionClass)
                if (blacklistEntry != null) {
                    LOG.trace("Quick fix $actionClass is blacklisted because of ${blacklistEntry.reason}")
                    true
                } else {
                    false
                }
            }
            .filterNot { modCommandAction -> intentionBlacklist.containsImplementation(modCommandAction.javaClass.name) }
            .map(converter)
            .flatMap { modCommandAction -> modCommandAction.toModCommandFixes(actionContext) }
            .map { fix -> applyFixCodeAction(fix.name, codeActionKind, fix.data) }
    }

    private val codeActionKind: CodeActionKind = CodeActionKind.Refactor

    private val languageIds: List<String> get() = supportedLanguages.map { language -> language.intellijLanguage.id }
}
