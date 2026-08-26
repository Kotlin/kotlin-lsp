// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.daemon.impl.InspectionVisitorOptimizer
import com.intellij.codeInspection.GlobalSimpleInspectionTool
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
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.helpers.use
import com.intellij.platform.diagnostic.telemetry.helpers.useWithScope
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.jetbrains.ls.api.core.LSAnalysisContext
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val onTheFly = true
        val diagnostics = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            class DiagnosticsRequestData(
                val virtualFile: VirtualFile,
                val psiFile: PsiFile,
                val elements: List<PsiElement>,
                val localInspections: List<LocalInspectionTool>,
                val globalInspections: List<GlobalSimpleInspectionTool>,
            )
            val requestData = readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                if (!ProblemHighlightFilter.shouldHighlightFile(psiFile)) return@readAction null
                val elements = arrayListOf<PsiElement>()
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        super.visitElement(element)
                        elements.add(element)
                    }
                })
                DiagnosticsRequestData(
                    virtualFile = virtualFile,
                    psiFile = psiFile,
                    elements = elements,
                    // A single session is shared by all inspections, mirroring InspectionEngine.withSession.
                    localInspections = lsInspectionManager.getLocalInspections(psiFile) +
                            lsInspectionManager.getSharedLocalInspectionsFromGlobalTools(psiFile.language),
                    globalInspections = lsInspectionManager.getSimpleGlobalInspections(psiFile.language),
                )
            } ?: return@withAnalysisContextAndFileSettings emptyList()

            // TODO(bartekpacia): centralize common logging so it's not repeated N times across all LS*Providers
            LOG.debug("request textDocument/diagnostic for ${requestData.virtualFile.name}")

            val inspectionManager = InspectionManagerEx(project)

            val localDiagnostics = runLocalInspections(
                inspectionManager,
                requestData.psiFile,
                requestData.localInspections,
                requestData.elements,
                onTheFly
            )
            val globalDiagnostics = runGlobalInspections(
                inspectionManager,
                requestData.psiFile,
                requestData.globalInspections,
                onTheFly
            )
            localDiagnostics + globalDiagnostics
        }
        diagnostics.forEach { diagnostic -> emit(diagnostic) }
    }

    /**
     * Runs every local inspection concurrently, one coroutine per inspection dispatched over the CPU pool.
     *
     * Each inspection gets its own [ProblemsHolder] (as in [com.intellij.codeInspection.InspectionEngine]),
     * while the elements list, the [InspectionVisitorOptimizer] and the [LocalInspectionToolSession] are
     * read-only and safely shared. Results preserve the inspection order because [awaitAll] keeps it.
     */
    context(server: LSServer, analysisContext: LSAnalysisContext)
    private suspend fun runLocalInspections(
        inspectionManager: InspectionManagerEx,
        psiFile: PsiFile,
        inspections: List<LocalInspectionTool>,
        elements: List<PsiElement>,
        onTheFly: Boolean,
    ): List<Diagnostic> = tracer.spanBuilder(SPAN_LOCAL_INSPECTIONS).useWithScope {
        val optimizer = InspectionVisitorOptimizer(elements)
        val document = readAction { psiFile.fileDocument }
        val fileRange = readAction { psiFile.textRange }
        val session = LocalInspectionToolSession(psiFile, fileRange, fileRange, null)
        coroutineScope {
            inspections.map { localInspection ->
                async {
                    readAction {
                        runInspection(kind = InspectionKind.Local, inspectionId = localInspection.id) {
                            val problemsHolder = ProblemsHolder(inspectionManager, psiFile, onTheFly)
                            val visitor = localInspection.buildVisitor(problemsHolder, onTheFly, session)
                            if (visitor == PsiElementVisitor.EMPTY_VISITOR) {
                                return@runInspection emptyList()
                            }
                            runCatching {
                                optimizer.acceptElements(elements, visitor)
                            }.getOrHandleException {
                                LOG.warn(it)
                            }
                            problemsHolder.collectDiagnostics(project, document, localInspection)
                        }
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Runs the simple global inspections sequentially.
     *
     * Unlike local inspections these are not parallelized: they share a single [InspectionManagerEx] whose
     * running-contexts bookkeeping is not thread-safe, and there are only a handful of them, so their cost is
     * negligible compared to the local inspection pass.
     */
    context(server: LSServer, analysisContext: LSAnalysisContext)
    private suspend fun runGlobalInspections(
        inspectionManager: InspectionManagerEx,
        psiFile: PsiFile,
        inspections: List<GlobalSimpleInspectionTool>,
        onTheFly: Boolean,
    ): List<Diagnostic> = readAction {
        tracer.spanBuilder(SPAN_GLOBAL_INSPECTIONS).use {
            val globalInspectionContext = inspectionManager.createNewGlobalContext()
            val document = psiFile.fileDocument
            inspections.flatMap { simpleGlobalInspection ->
                runInspection(kind = InspectionKind.Global, inspectionId = simpleGlobalInspection.shortName) {
                    val problemsHolder = ProblemsHolder(inspectionManager, psiFile, onTheFly)
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
                    val diagnostics = problemsHolder.results.mapNotNull { problemDescriptor ->
                        val data = lsInspectionManager.createDiagnosticData(problemDescriptor, project)
                        val range = problemDescriptor.range()?.toLspRange(document) ?: return@mapNotNull null
                        val message = ProblemDescriptorUtil.renderDescriptor(
                            problemDescriptor, problemDescriptor.psiElement, ProblemDescriptorUtil.NONE
                        )
                        Diagnostic(
                            range = range,
                            severity = problemDescriptor.highlightType.toLspSeverity(),
                            message = message.description,
                            code = StringOrInt.string(simpleGlobalInspection.shortName),
                            tags = problemDescriptor.highlightType.toLspTags(),
                            data = LSP.json.encodeToJsonElement<SimpleDiagnosticData>(data),
                        )
                    }
                    diagnostics
                }
            }
        }
    }

    private fun runInspection(kind: InspectionKind, inspectionId: String, block: () -> List<Diagnostic>): List<Diagnostic> {
        return tracer.spanBuilder(kind.spanName)
            .setAttribute("inspection.kind", kind.attributeValue)
            .setAttribute("inspection.id", inspectionId)
            .use { span ->
                val produced = block()
                span.setAttribute("diagnostics.count", produced.size.toLong())
                produced
            }
    }

    context(server: LSServer)
    private fun ProblemsHolder.collectDiagnostics(
        project: Project,
        document: Document,
        localInspectionTool: LocalInspectionTool,
    ): List<Diagnostic> {
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
