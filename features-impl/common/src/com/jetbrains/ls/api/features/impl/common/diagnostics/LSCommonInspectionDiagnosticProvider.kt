// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionEP
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.QuickFix
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.lang.Language
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.ReportingClassSubstitutor
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.utils.toLspSeverity
import com.jetbrains.ls.api.features.impl.common.utils.toLspTags
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

private val LOG = logger<LSCommonInspectionDiagnosticProvider>()

// TODO: LSP-278 Optimize performance of inspections
class LSCommonInspectionDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionBlacklist: Blacklist = Blacklist(),
    private val quickFixBlacklist: Blacklist = Blacklist(),
) : LSDiagnosticProvider {
    companion object {
        val diagnosticSource: DiagnosticSource = DiagnosticSource("inspection")
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val onTheFly = false
        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val diagnostics = mutableListOf<Diagnostic>()

                // TODO(bartekpacia): centralize common logging so it's not repeated N times across all LS*Providers
                LOG.debug("start request textDocument/diagnostic for ${params.textDocument.uri.uri}")

                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                val inspectionManager = InspectionManagerEx(project)
                val problemsHolder = ProblemsHolder(inspectionManager, psiFile, onTheFly)

                val localInspections = getLocalInspections(psiFile) + getSharedLocalInspectionsFromGlobalTools(psiFile.language)
                LOG.debug("running ${localInspections.size} local inspections")
                for (localInspection in localInspections) {
                    val visitor = localInspection.buildVisitor(problemsHolder, onTheFly)

                    fun collect(element: PsiElement) {
                        runCatching {
                            element.accept(visitor)
                        }.getOrHandleException {
                            LOG.warn(it)
                        }
                        diagnostics.addAll(problemsHolder.collectDiagnostics(virtualFile, project, localInspection))
                        problemsHolder.clearResults()
                    }

                    LOG.trace("running local inspection ${localInspection.id}")
                    psiFile.accept(object : PsiElementVisitor() {
                        override fun visitElement(element: PsiElement) {
                            collect(element)
                            element.acceptChildren(this)
                        }
                    })
                }
                LOG.debug("done running local inspections")

                val globalInspectionContext = inspectionManager.createNewGlobalContext()
                val globalInspections = getSimpleGlobalInspections(psiFile.language)
                LOG.debug("running ${globalInspections.size} simple global inspections")
                for (simpleGlobalInspection in globalInspections) {
                    val processor = object : ProblemDescriptionsProcessor {}
                    LOG.trace("running simple global inspection ${simpleGlobalInspection.shortName}")
                    runCatching {
                        simpleGlobalInspection.checkFile(psiFile, inspectionManager, problemsHolder, globalInspectionContext, processor)
                    }.getOrHandleException {
                        LOG.warn(it)
                    }
                    for (problemDescriptor in problemsHolder.results) {
                        val data = problemDescriptor.createDiagnosticData(project)
                        val range = problemDescriptor.range()?.toLspRange(document) ?: continue
                        val message = ProblemDescriptorUtil.renderDescriptor(
                            problemDescriptor, problemDescriptor.psiElement, ProblemDescriptorUtil.NONE
                        )
                        diagnostics.add(
                            Diagnostic(
                                range = range,
                                severity = problemDescriptor.highlightType.toLspSeverity(),
                                message = message.description,
                                code = StringOrInt.string(simpleGlobalInspection.shortName),
                                tags = problemDescriptor.highlightType.toLspTags(),
                                data = LSP.json.encodeToJsonElement<SimpleDiagnosticData>(data),
                            ),
                        )
                    }
                    problemsHolder.clearResults()
                }
                LOG.debug("done running simple global inspections")

                return@readAction diagnostics
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }

    private fun getEnabledInspectionTools(extensionList: List<InspectionEP>, languageId: String): Sequence<InspectionProfileEntry> {
        return extensionList
            .asSequence()
            .filter { inspectionEP -> inspectionEP.language == languageId }
            .filter { inspectionEP -> inspectionEP.enabledByDefault }
            .filter { inspectionEP -> HighlightDisplayLevel.find(inspectionEP.level) != HighlightDisplayLevel.DO_NOT_SHOW }
            .filterNot { inspectionBlacklist.containsImplementation(it.implementationClass) }
            .mapNotNull { inspectionEP ->
                runCatching {
                    inspectionEP.instantiateTool()
                }.getOrHandleException {
                    LOG.warn(it)
                }
            }
            .filterNot { inspectionBlacklist.containsSuperClass(it) }
    }

    private fun getLocalInspections(psiFile: PsiFile): List<LocalInspectionTool> {
        return getEnabledInspectionTools(LocalInspectionEP.LOCAL_INSPECTION.extensionList, psiFile.language.id)
            .filterIsInstance<LocalInspectionTool>()
            .filter { localInspectionTool -> localInspectionTool.isAvailableForFile(psiFile) }
            .toList()
    }

    private fun getSimpleGlobalInspections(language: Language): List<GlobalSimpleInspectionTool> {
        return getEnabledInspectionTools(InspectionEP.GLOBAL_INSPECTION.extensionList, language.id)
            .filterIsInstance<GlobalSimpleInspectionTool>()
            .toList()
    }

    private fun getSharedLocalInspectionsFromGlobalTools(language: Language): List<LocalInspectionTool> {
        return getEnabledInspectionTools(InspectionEP.GLOBAL_INSPECTION.extensionList, language.id)
            .filterIsInstance<GlobalInspectionTool>()
            .mapNotNull { globalInspectionTool -> globalInspectionTool.sharedLocalInspectionTool }
            .filterNot { inspectionBlacklist.containsSuperClass(it) }
            .toList()
    }

    private fun ProblemsHolder.collectDiagnostics(
        file: VirtualFile,
        project: Project,
        localInspectionTool: LocalInspectionTool,
    ): List<Diagnostic> {
        val document = file.findDocument() ?: return emptyList()
        return results
            .filter { problemDescriptor -> problemDescriptor.highlightType != ProblemHighlightType.INFORMATION }
            .mapNotNull { problemDescriptor ->
                val data = problemDescriptor.createDiagnosticData(project)
                val message = ProblemDescriptorUtil.renderDescriptor(
                    problemDescriptor, problemDescriptor.psiElement, ProblemDescriptorUtil.NONE
                )
                Diagnostic(
                    range = problemDescriptor.range()?.toLspRange(document) ?: return@mapNotNull null,
                    severity = problemDescriptor.highlightType.toLspSeverity(),
                    message = message.description,
                    code = StringOrInt.string(localInspectionTool.id),
                    tags = problemDescriptor.highlightType.toLspTags(),
                    data = LSP.json.encodeToJsonElement(data),
                )
            }
    }

    private fun ProblemDescriptor.createDiagnosticData(project: Project): SimpleDiagnosticData {
        return SimpleDiagnosticData(
            diagnosticSource = diagnosticSource,
            fixes = fixes.orEmpty().mapNotNull { quickFix ->
                val modCommand = getModCommand(quickFix, project, this) ?: return@mapNotNull null
                val modCommandData = ModCommandData.from(modCommand) ?: return@mapNotNull null
                SimpleDiagnosticQuickfixData(name = quickFix.name, modCommandData = modCommandData)
            },
        )
    }

    private fun getModCommand(fix: QuickFix<*>, project: Project, problemDescriptor: ProblemDescriptor): ModCommand? {
        val fixClass = ReportingClassSubstitutor.getClassToReport(fix).name
        val blacklistEntry = quickFixBlacklist.getImplementationBlacklistEntry(fixClass)

        if (fix is ModCommandQuickFix) {
            if (blacklistEntry != null) {
                LOG.debug("Quick fix $fixClass is a ModCommandQuickFix, but it is blacklisted because of ${blacklistEntry.reason}")
                return null
            }

            return fix.perform(project, problemDescriptor)
        }

        if (fix is IntentionAction) {
            if (blacklistEntry != null) {
                LOG.debug("Quick fix $fixClass is an IntentionAction, but it is blacklisted because of ${blacklistEntry.reason}")
                return null
            }

            val modCommandAction = fix.asModCommandAction()
            if (modCommandAction != null) {
                return modCommandAction.perform(ActionContext.from(problemDescriptor))
            }
        }

        if (blacklistEntry == null) {
            LOG.warn("Unknown quick fix type: $fixClass. Please add it to the blacklist and create a YouTrack issue.")
        }

        return null
    }
}

private fun ProblemDescriptor.range(): TextRange? {
    val element = psiElement ?: return null
    val elementRange = element.textRange ?: return null
    // relative range -> absolute range
    textRangeInElement?.let { return it.shiftRight(elementRange.startOffset) }
    return elementRange
}
