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
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.Blacklist
import com.jetbrains.ls.api.features.impl.common.diagnostics.DiagnosticSource
import com.jetbrains.ls.api.features.impl.common.diagnostics.SimpleDiagnosticData
import com.jetbrains.ls.api.features.impl.common.diagnostics.SimpleDiagnosticQuickfixData
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

class LSCommonInspectionDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val blacklist: Blacklist = Blacklist(),
) : LSDiagnosticProvider {
    companion object {
        val diagnosticSource: DiagnosticSource = DiagnosticSource("inspection")
    }

    context(_: LSServer, _: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val onTheFly = false
        withAnalysisContext(params.textDocument.uri.uri) {
            readAction c@{
                val diagnostics = mutableListOf<Diagnostic>()

                val file = params.textDocument.findVirtualFile() ?: return@c emptyList()
                val document = file.findDocument() ?: return@c emptyList()
                val psiFile = file.findPsiFile(project) ?: return@c emptyList()
                val inspectionManager = InspectionManagerEx(project)
                val problemsHolder = ProblemsHolder(inspectionManager, psiFile, onTheFly)

                val localInspections = getLocalInspections(psiFile)
                for (localInspection in localInspections) {
                    val visitor = localInspection.buildVisitor(problemsHolder, onTheFly)

                    fun collect(element: PsiElement) {
                        runCatching {
                            element.accept(visitor)
                        }.getOrHandleException {
                            handleError(it)
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
                        handleError(it)
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

                return@c diagnostics
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }

    private fun getLocalInspections(psiFile: PsiFile): List<LocalInspectionTool> {
        return LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .asSequence()
            .filter { it.language == psiFile.language.id }
            .filter { it.enabledByDefault }
            .filter { HighlightDisplayLevel.find(it.level) != HighlightDisplayLevel.DO_NOT_SHOW }
            .filterNot { blacklist.containsImplementation(it.implementationClass) }
            .mapNotNull { inspection ->
                runCatching {
                    inspection.instantiateTool()
                }.getOrHandleException {
                    handleError(it)
                }
            }
            .filterNot { blacklist.containsSuperClass(it) }
            .filterIsInstance<LocalInspectionTool>()
            .filter { localInspection -> localInspection.isAvailableForFile(psiFile) }
            .toList()
    }

    private fun getSimpleGlobalInspections(language: Language): List<GlobalSimpleInspectionTool> {
        return InspectionEP.GLOBAL_INSPECTION.extensionList
            .asSequence()
            .filter { it.language == language.id }
            .filter { it.enabledByDefault }
            .filter { HighlightDisplayLevel.find(it.level) != HighlightDisplayLevel.DO_NOT_SHOW }
            .filterNot { blacklist.containsImplementation(it.implementationClass) }
            .mapNotNull { inspection ->
                runCatching {
                    inspection.instantiateTool()
                }.getOrHandleException {
                    handleError(it)
                }
            }
            .filterNot { blacklist.containsSuperClass(it) }
            .filterIsInstance<GlobalSimpleInspectionTool>()
            .toList()
    }

    private fun ProblemsHolder.collectDiagnostics(
        file: VirtualFile,
        project: Project,
        localInspectionTool: LocalInspectionTool
    ): List<Diagnostic> {
        val document = file.findDocument() ?: return emptyList()
        return results
            .filter { it.highlightType != ProblemHighlightType.INFORMATION }
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
            fixes = fixes.orEmpty().mapNotNull { fix ->
                    val modCommand = getModCommand(fix, project, this) ?: return@mapNotNull null
                    val modCommandData = ModCommandData.from(modCommand) ?: return@mapNotNull null
                    SimpleDiagnosticQuickfixData(name = fix.name, modCommandData = modCommandData)
                },
        )
    }
}

private fun getModCommand(fix: QuickFix<*>, project: Project, problemDescriptor: ProblemDescriptor): ModCommand? =
    when (fix) {
        is ModCommandQuickFix ->
            fix.perform(project, problemDescriptor)
        else -> {
            LOG.debug("Unknown quick fix type: ${fix::class.java}")
            null
        }
    }

private fun handleError(throwable: Throwable) {
    when {
        throwable is LinkageError -> LOG.error(throwable)
        LOG.isTraceEnabled -> LOG.warn(throwable)
        else -> LOG.warn(throwable.toString())
    }
}

private val LOG = logger<LSCommonInspectionDiagnosticProvider>()
