// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.SuppressIntentionActionFromFix
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
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
import com.jetbrains.ls.api.core.features.LSConfiguredInspection
import com.jetbrains.ls.api.core.features.LSInspections
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.codeActions.LSCodeActionGroup
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCodeAction
import com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val LOG = logger<LSCommonInspectionOptionsCodeActionProvider>()

/**
 * Offers the `Inspection 'name'...` action of an inspection diagnostic. The action shows a chooser of the inspection
 * options: the "Suppress for statement / method / class / file" actions. The IDE shows the same options after the
 * fixes of the warning (see `HighlightInfo.IntentionActionDescriptor.getOptions`).
 *
 * The action is offered for every inspection diagnostic, also for one without fixes. The options are found when the
 * client asks for the code actions of a diagnostic, not when the diagnostic is reported: the client asks for the
 * actions of a few warnings only.
 */
class LSCommonInspectionOptionsCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
) : LSCodeActionProvider {

    companion object {
        /** The kind of the `Inspection 'name'...` actions, which a client can tell from the fixes of a diagnostic. */
        val inspectionOptionsKind: CodeActionKind = CodeActionKind("${CodeActionKind.QuickFix.value}.inspection")

        fun isInspectionOptions(codeAction: CodeAction): Boolean = codeAction.kind == inspectionOptionsKind
    }

    override val providesOnlyKinds: Set<CodeActionKind> = setOf(inspectionOptionsKind)

    override val group: LSCodeActionGroup get() = LSCodeActionGroup.InspectionOptions

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        if (!params.textDocument.isSource()) return@flow
        val withData = params.diagnosticData<SimpleDiagnosticData>()
            .filter { LSCommonInspectionDiagnosticProvider.isInspectionSource(it.data.diagnosticSource) }
        if (withData.isEmpty()) return@flow
        val diagnostics = withData.map { it.diagnostic }
        // A batch run may run a tool that is not enabled by default (Run Inspection by Name), so its tools are found by name.
        val (batch, live) = withData.partition { it.data.diagnosticSource == LSCommonInspectionDiagnosticProvider.batchDiagnosticSource }
        val batchIds = batch.mapNotNullTo(mutableSetOf()) { it.diagnostic.code?.value?.content }
        val importInspectionProfile = server.config.importInspectionProfile

        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                // The same tools the diagnostic provider runs, by the id it reports as the diagnostic code.
                val liveTools = if (live.isEmpty()) emptyList() else
                    LSInspections.getLocalInspections(
                        psiFile, inspectionProfilePatcher, importInspectionProfile = importInspectionProfile,
                    ) +
                    LSInspections.getSharedLocalInspectionsFromGlobalTools(
                        psiFile, psiFile.language, inspectionProfilePatcher, importInspectionProfile = importInspectionProfile,
                    )
                val tools = (liveTools + batchTools(psiFile, batchIds, importInspectionProfile)).associateBy { it.tool.id }
                diagnostics.flatMap { diagnostic ->
                    val tool = tools[diagnostic.code?.value?.content] ?: return@flatMap emptyList()
                    val offset = document.offsetByPosition(diagnostic.range.start)
                    val element = psiFile.findElementAt(offset) ?: return@flatMap emptyList()
                    val context = ActionContext(psiFile.project, psiFile, offset, TextRange(offset, offset), element)
                    val options = suppressionActions(tool.tool, element)
                    val action = InspectionOptionsAction(displayName(tool.tool), options)
                    action.toModCommandFixes(context).map { fix ->
                        applyFixCodeAction(fix.name, inspectionOptionsKind, fix.data, diagnostic)
                    }
                }
            }
        }.forEach { codeAction -> emit(codeAction) }
    }

    /** The tools named by [inspectionIds], the codes of batch diagnostics; none without codes. */
    private fun batchTools(
        psiFile: PsiFile,
        inspectionIds: Set<String>,
        importInspectionProfile: Boolean,
    ): List<LSConfiguredInspection<LocalInspectionTool>> {
        if (inspectionIds.isEmpty()) return emptyList()
        return LSInspections.getLocalInspections(
                   psiFile, inspectionProfilePatcher, inspectionIds = inspectionIds, importInspectionProfile = importInspectionProfile,
               ) +
               LSInspections.getSharedLocalInspectionsFromGlobalTools(
                   psiFile, psiFile.language, inspectionProfilePatcher, inspectionIds = inspectionIds,
                   importInspectionProfile = importInspectionProfile,
               )
    }

    /** The suppression actions of [tool] at [element], in the order the IDE shows them. */
    private fun suppressionActions(tool: InspectionProfileEntry, element: PsiElement): List<ModCommandAction> {
        val suppressFixes = runCatching {
            tool.getBatchSuppressActions(element)
        }.getOrHandleException {
            LOG.warn("Failed to get suppression actions of ${tool.shortName}", it)
        } ?: return emptyList()
        return suppressFixes
            .map { fix -> fix to SuppressIntentionActionFromFix.convertBatchToSuppressIntentionAction(fix) as SuppressIntentionActionFromFix }
            .sortedWith { (_, a), (_, b) -> a.compareTo(b) }
            .map { (fix, intention) -> intention.asModCommandAction() ?: SuppressOnCopyAction(tool, fix.name, fix.familyName) }
    }

    private fun displayName(tool: InspectionProfileEntry): String =
        runCatching { tool.displayName }.getOrHandleException { LOG.warn("Failed to get the name of ${tool.shortName}", it) }
        ?: tool.shortName
}

/**
 * The `Inspection 'name'...` action: a chooser of the [options] of the inspection named [inspectionName].
 * It is available when one of the options is available.
 */
private class InspectionOptionsAction(
    private val inspectionName: String,
    private val options: List<ModCommandAction>,
) : ModCommandAction {
    override fun getFamilyName(): String = LspServerBundle.message("inspection.options.action.name", inspectionName)

    override fun getPresentation(context: ActionContext): Presentation? {
        val available = options.any { option ->
            runCatching { option.getPresentation(context) }.getOrHandleException {
                LOG.warn("Failed to get presentation from mod command action $option", it)
            } != null
        }
        return if (available) Presentation.of(familyName) else null
    }

    override fun perform(context: ActionContext): ModCommand =
        ModChooseAction(LspServerBundle.message("inspection.options.chooser.title", inspectionName), options)
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
