// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.helpers.use
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.diagnostics.LSDiagnostic
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.utils.toLspSeverity
import com.jetbrains.ls.api.features.impl.common.utils.toLspTags
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.DocumentDiagnosticParams
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

private val LOG = logger<LSCommonInspectionDiagnosticProvider>()

private enum class InspectionKind(val attributeValue: String, val spanName: String) {
    Local("local", "diagnostics.localInspection"),
    Global("global", "diagnostics.globalInspection"),
}

// TODO: LSP-278 Optimize performance of inspections
class LSCommonInspectionDiagnosticProvider(
    override val supportedLanguages: Set<LSLanguage>,
    inspectionBlacklist: Blacklist = Blacklist(),
    quickFixBlacklist: Blacklist = Blacklist(),
) : LSDiagnosticProvider {
    private val lsInspectionManager = LSInspectionManager(inspectionBlacklist, quickFixBlacklist)
    
    companion object {
        val diagnosticSource: DiagnosticSource = DiagnosticSource("inspection")

        private const val SPAN_LOCAL_INSPECTIONS = "diagnostics.runLocalInspections"
        private const val SPAN_GLOBAL_INSPECTIONS = "diagnostics.runGlobalInspections"

        private val tracer = TelemetryManager.getTracer(LSDiagnostic.scope)
    }

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        if (!params.textDocument.isSource()) return@flow
        val onTheFly = false
        server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val diagnostics = mutableListOf<Diagnostic>()

                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction emptyList()
                if (!ProblemHighlightFilter.shouldHighlightFile(psiFile)) return@readAction emptyList()

                // TODO(bartekpacia): centralize common logging so it's not repeated N times across all LS*Providers
                LOG.debug("request textDocument/diagnostic for ${virtualFile.name}")

                val inspectionManager = InspectionManagerEx(project)
                val problemsHolder = ProblemsHolder(inspectionManager, psiFile, onTheFly)

                tracer.spanBuilder(SPAN_LOCAL_INSPECTIONS).use {
                    val localInspections = lsInspectionManager.getLocalInspections(psiFile) +
                            lsInspectionManager.getSharedLocalInspectionsFromGlobalTools(psiFile.language)
                    val fileRange = psiFile.textRange
                    val session = LocalInspectionToolSession(psiFile, fileRange, fileRange, null)
                    for (localInspection in localInspections) {
                        runInspection(kind = InspectionKind.Local, inspectionId = localInspection.id) {
                            val diagnosticsBeforeInspection = diagnostics.size
                            val visitor = localInspection.buildVisitor(problemsHolder, onTheFly, session)

                            fun collect(element: PsiElement) {
                                runCatching {
                                    element.accept(visitor)
                                }.getOrHandleException {
                                    LOG.warn(it)
                                }
                                diagnostics.addAll(problemsHolder.collectDiagnostics(virtualFile, project, localInspection))
                                problemsHolder.clearResults()
                            }

                            psiFile.accept(object : PsiElementVisitor() {
                                override fun visitElement(element: PsiElement) {
                                    collect(element)
                                    element.acceptChildren(this)
                                }
                            })
                            diagnostics.size - diagnosticsBeforeInspection
                        }
                    }
                }

                tracer.spanBuilder(SPAN_GLOBAL_INSPECTIONS).use {
                    val globalInspectionContext = inspectionManager.createNewGlobalContext()
                    val globalInspections = lsInspectionManager.getSimpleGlobalInspections(psiFile.language)
                    for (simpleGlobalInspection in globalInspections) {
                        runInspection(kind = InspectionKind.Global, inspectionId = simpleGlobalInspection.shortName) {
                            val diagnosticsBeforeInspection = diagnostics.size
                            val processor = object : ProblemDescriptionsProcessor {}
                            runCatching {
                                simpleGlobalInspection.checkFile(
                                    /* psiFile = */ psiFile,
                                    /* manager = */ inspectionManager,
                                    /* problemsHolder = */ problemsHolder,
                                    /* globalContext = */ globalInspectionContext,
                                    /* problemDescriptionsProcessor = */ processor,
                                )
                            }.getOrHandleException {
                                LOG.warn(it)
                            }
                            for (problemDescriptor in problemsHolder.results) {
                                val data = lsInspectionManager.createDiagnosticData(problemDescriptor, project)
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
                            diagnostics.size - diagnosticsBeforeInspection
                        }
                    }

                    diagnostics
                }
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }

    private fun runInspection(kind: InspectionKind, inspectionId: String, block: () -> Int) {
        tracer.spanBuilder(kind.spanName)
            .setAttribute("inspection.kind", kind.attributeValue)
            .setAttribute("inspection.id", inspectionId)
            .use { span ->
                val produced = block()
                span.setAttribute("diagnostics.count", produced.toLong())
            }
    }

    private fun ProblemsHolder.collectDiagnostics(
        file: VirtualFile,
        project: Project,
        localInspectionTool: LocalInspectionTool,
    ): List<Diagnostic> {
        val document = file.findDocument() ?: return emptyList()
        return results
            .filter { problemDescriptor -> problemDescriptor.highlightType != ProblemHighlightType.INFORMATION }
            .filter { !isSuppressed(localInspectionTool, it) }
            .mapNotNull { problemDescriptor ->
                val data = lsInspectionManager.createDiagnosticData(problemDescriptor, project)
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
}

private fun ProblemDescriptor.range(): TextRange? {
    val element = psiElement ?: return null
    val elementRange = element.textRange ?: return null
    // relative range -> absolute range
    textRangeInElement?.let { return it.shiftRight(elementRange.startOffset) }
    return elementRange
}
