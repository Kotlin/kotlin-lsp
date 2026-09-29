// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.SuppressIntentionActionFromFix
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.lsGetLocalInspections
import com.jetbrains.ls.api.core.features.lsGetSharedLocalInspectionsFromGlobalTools
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.ModCommandFix
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val LOG = logger<LSCommonInspectionSuppressionCodeActionProvider>()

/**
 * Offers the "Suppress for statement / method / class / file" actions of an inspection diagnostic, which the IDE
 * lists after the fixes of the warning (see `HighlightInfo.IntentionActionDescriptor.getOptions`).
 *
 * The actions are found when the client asks for the code actions of a diagnostic, not when the diagnostic is
 * reported: every warning has several of them, and the client asks for the actions of a few warnings only.
 */
class LSCommonInspectionSuppressionCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
) : LSCodeActionProvider {

    companion object {
        /** The [CodeAction.data] of a suppression, which tells it from a fix of the same [CodeActionKind.QuickFix] kind. */
        val suppressionData: JsonElement = buildJsonObject { put("suppression", true) }

        fun isSuppression(codeAction: CodeAction): Boolean = codeAction.data == suppressionData
    }

    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    override val listedLast: Boolean get() = true

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        if (!params.textDocument.isSource()) return@flow
        val diagnostics = params.diagnosticData<SimpleDiagnosticData>()
            .filter { it.data.diagnosticSource == LSCommonInspectionDiagnosticProvider.diagnosticSource }
            .map { it.diagnostic }
        if (diagnostics.isEmpty()) return@flow

        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                // The same tools the diagnostic provider runs, by the id it reports as the diagnostic code.
                val tools = (lsGetLocalInspections(psiFile, inspectionProfilePatcher) +
                             lsGetSharedLocalInspectionsFromGlobalTools(psiFile.language, inspectionProfilePatcher))
                    .associateBy { it.id }
                diagnostics.flatMap { diagnostic ->
                    val tool = tools[diagnostic.code?.value?.content] ?: return@flatMap emptyList()
                    val offset = document.offsetByPosition(diagnostic.range.start)
                    suppressionFixes(tool, psiFile, offset).map { fix ->
                        applyFixCodeAction(fix.name, CodeActionKind.QuickFix, fix.data, diagnostic).copy(data = suppressionData)
                    }
                }
            }
        }.forEach { codeAction -> emit(codeAction) }
    }

    /** The suppression actions of [tool] at [offset], in the order the IDE shows them. */
    context(server: LSServer)
    private fun suppressionFixes(tool: InspectionProfileEntry, psiFile: PsiFile, offset: Int): List<ModCommandFix> {
        val element = psiFile.findElementAt(offset) ?: return emptyList()
        val context = ActionContext(psiFile.project, psiFile, offset, TextRange(offset, offset), element)
        val suppressFixes = runCatching {
            tool.getBatchSuppressActions(element)
        }.getOrHandleException {
            LOG.warn("Failed to get suppression actions of ${tool.shortName}", it)
        } ?: return emptyList()
        return suppressFixes
            .map { fix -> fix to SuppressIntentionActionFromFix.convertBatchToSuppressIntentionAction(fix) as SuppressIntentionActionFromFix }
            .sortedWith { (_, a), (_, b) -> a.compareTo(b) }
            .flatMap { (fix, intention) ->
                val action = intention.asModCommandAction() ?: SuppressOnCopyAction(tool, fix.name, fix.familyName)
                action.toModCommandFixes(context, name = fix.name)
            }
    }
}

/**
 * A [SuppressQuickFix] which is not a [com.intellij.modcommand.ModCommandQuickFix], such as the Kotlin `@Suppress`
 * one, run on a copy of the file.
 *
 * Such a fix changes the PSI it was created for, so it is created again for the copy: the fix of the same [name]
 * that [tool] offers for the element in the copy.
 */
private class SuppressOnCopyAction(
    private val tool: InspectionProfileEntry,
    private val name: String,
    private val familyName: String,
) : ModCommandAction {
    override fun getFamilyName(): String = familyName

    override fun getPresentation(context: ActionContext): Presentation? {
        val element = context.findLeaf() ?: return null
        val fix = findFix(element) ?: return null
        if (!fix.isAvailable(context.project(), element)) return null
        return Presentation.of(name)
    }

    override fun perform(context: ActionContext): ModCommand {
        val element = context.findLeaf() ?: return ModCommand.nop()
        return ModCommand.psiUpdate(context) { updater ->
            val copy = updater.getWritable(element)
            val fix = findFix(copy) ?: return@psiUpdate
            val descriptor = InspectionManager.getInstance(context.project())
                .createProblemDescriptor(copy, copy, "", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)
            fix.applyFix(context.project(), descriptor)
        }
    }

    private fun findFix(element: PsiElement): SuppressQuickFix? =
        tool.getBatchSuppressActions(element).firstOrNull { it.name == name }
}
