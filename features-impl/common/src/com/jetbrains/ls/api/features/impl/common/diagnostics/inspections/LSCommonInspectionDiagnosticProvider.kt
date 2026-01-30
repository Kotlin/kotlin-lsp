// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.lang.Language
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.application.readAction
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
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.Blacklist
import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticSource
import com.jetbrains.ls.api.features.impl.common.diagnostics.SimpleDiagnosticData
import com.jetbrains.ls.api.features.impl.common.diagnostics.SimpleDiagnosticQuickfixData
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSCommonInspectionDiagnosticProvider.Companion.diagnosticSource
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

class LSCommonInspectionDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val blacklist: Blacklist = Blacklist(),
) : LSDiagnosticProvider {
    companion object {
        val diagnosticSource: DiagnosticSource = DiagnosticSource("inspection")
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val onTheFly = false
        server.withAnalysisContext(params.textDocument.uri.uri) {
            readAction {
                val diagnostics = mutableListOf<Diagnostic>()

                val file = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = file.findDocument() ?: return@readAction emptyList()
                val psiFile = file.findPsiFile(project) ?: return@readAction emptyList()
                val inspectionManager = InspectionManagerEx(project)
                val problemsHolder = ProblemsHolder(inspectionManager, psiFile, onTheFly)

                for (localInspection in getLocalInspections(psiFile) + getSharedLocalInspectionsFromGlobalTools(psiFile.language)) {
                    val visitor = localInspection.buildVisitor(problemsHolder, onTheFly)

                    fun collect(element: PsiElement) {
                        runCatching {
                            element.accept(visitor)
                        }.getOrHandleException {
                            LOG.warn(it)
                        }
                        diagnostics.addAll(problemsHolder.collectDiagnostics(file, project, localInspection))
                        problemsHolder.clearResults()
                    }

                    psiFile.accept(object : PsiElementVisitor() {
                        override fun visitElement(element: PsiElement) {
                            collect(element)
                            element.acceptChildren(this)
                        }
                    })
                }

                val globalInspectionContext = inspectionManager.createNewGlobalContext()
                for (simpleGlobalInspection in getSimpleGlobalInspections(psiFile.language)) {
                    val processor = object : ProblemDescriptionsProcessor {}
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
            .filterNot { blacklist.containsImplementation(it.implementationClass) }
            .mapNotNull { inspectionEP ->
                runCatching {
                    inspectionEP.instantiateTool()
                }.getOrHandleException {
                    LOG.warn(it)
                }
            }
            .filterNot { blacklist.containsSuperClass(it) }
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
            .filterNot { blacklist.containsSuperClass(it) }
            .toList()
    }
}

private fun ProblemsHolder.collectDiagnostics(
    file: VirtualFile,
    project: Project,
    localInspectionTool: LocalInspectionTool
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

private fun ProblemDescriptor.range(): TextRange? {
    val element = psiElement ?: return null
    val elementRange = element.textRange ?: return null
    // relative range -> absolute range
    textRangeInElement?.let { return it.shiftRight(elementRange.startOffset) }
    return elementRange
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
    return when (fix) {
        is ModCommandQuickFix -> fix.perform(project, problemDescriptor)
        else -> {
            LOG.warn("Unknown quick fix type: ${fix::class.java}")
            null
        }
    }
}
