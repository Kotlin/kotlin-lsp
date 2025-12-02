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
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.reflect.KClass

class LSInspectionDiagnosticProviderImpl(
    override val supportedLanguages: Set<LSLanguage>,
) : LSDiagnosticProvider {
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

                val localInspections = getLocalInspections(psiFile.language)
                for (localInspection in localInspections) {
                    val visitor = localInspection.buildVisitor(problemsHolder, onTheFly)

                    fun collect(element: PsiElement) {
                        runCatching {
                            element.accept(visitor)
                        }.getOrHandleException {
                            if (LOG.isTraceEnabled) LOG.warn(it)
                            else LOG.warn(it.toString())
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
                        if (LOG.isTraceEnabled) LOG.warn(it)
                        else LOG.warn(it.toString())
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
                                severity = problemDescriptor.highlightType.toLsp(),
                                message = message.description,
                                code = StringOrInt.string(simpleGlobalInspection.shortName),
                                tags = problemDescriptor.highlightType.toLspTags(),
                                data = LSP.json.encodeToJsonElement<InspectionDiagnosticData>(data),
                            ),
                        )
                    }
                    problemsHolder.clearResults()
                }

                return@c diagnostics
            }
        }.forEach { diagnostic -> emit(diagnostic) }
    }

    // kotlin ones should be moved closer to the kotlin impl
    private val blacklist = BlackList(
        // Kotlin local inspections
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveRedundantQualifierNameInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.UnusedVariableInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveExplicitTypeArgumentsInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.K2MemberVisibilityCanBePrivateInspection", "slow, performs find usages"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.VariableNeverReadInspection", "very slow, uses extended checkers"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection", "very slow, uses extended checkers"),

        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PublicApiImplicitTypeInspection", "too noisy https://github.com/Kotlin/kotlin-lsp/issues/4"),

        BlackListEntry.InspectionSuperClass("org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase", "they are slow as calling additional diagnostic collection"),
        BlackListEntry.InspectionSuperClass("org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase", "they are slow as calling additional diagnostic collection"),

        // Java local inspections
        BlackListEntry.InspectionClass("com.siyeh.ig.bugs.WriteOnlyObjectInspection", "depends on ContractInferenceIndexKt which depends on GistManager"),
        BlackListEntry.InspectionClass("com.siyeh.ig.bugs.MismatchedCollectionQueryUpdateInspection", "depends on ImplicitUsagesProvider"),
        BlackListEntry.InspectionSuperClass("com.intellij.codeInspection.nullable.NullableStuffInspectionBase", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.intellij.codeInspection.nullable.NotNullFieldNotInitializedInspection", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.siyeh.ig.controlflow.IfStatementWithIdenticalBranchesInspection", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.siyeh.ig.threading.DoubleCheckedLockingInspection", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.siyeh.ig.controlflow.DuplicateConditionInspection", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.siyeh.ig.migration.IfCanBeSwitchInspection", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.siyeh.ig.controlflow.PointlessNullCheckInspection", "depends on NullableNotNullManager"),
        BlackListEntry.InspectionClass("com.intellij.codeInspection.bulkOperation.UseBulkOperationInspection", "depends on BulkMethodInfoProvider"),
        BlackListEntry.InspectionClass("com.intellij.codeInspection.UpdateInspectionOptionFix", "depends on PathMacrosImpl"),
        BlackListEntry.InspectionClass("com.siyeh.ig.annotation.MetaAnnotationWithoutRuntimeRetentionInspection", "depends on UastLanguagePlugin"),
        BlackListEntry.InspectionClass("com.siyeh.ig.bugs.IgnoreResultOfCallInspection", "depends on ProjectBytecodeAnalysis"),
        BlackListEntry.InspectionClass("com.siyeh.ig.style.FieldMayBeFinalInspection", "Missing extension point com.intellij.canBeFinal"),
        BlackListEntry.InspectionClass("com.siyeh.ig.controlflow.PointlessBooleanExpressionInspection", "Missing extension point: com.intellij.canBeFinal"),
        BlackListEntry.InspectionClass("com.siyeh.ig.maturity.CommentedOutCodeInspection", "depends on JavaCodeFragmentFactory"),
        BlackListEntry.InspectionClass("com.intellij.codeInspection.java18api.Java8MapApiInspection", "Missing extension point: com.intellij.deepestSuperMethodsSearch"),
        BlackListEntry.InspectionClass("com.siyeh.ig.dataflow.UnnecessaryLocalVariableInspection", "depends on CommonJavaInlineUtil"),
        BlackListEntry.InspectionClass("com.siyeh.ig.controlflow.ExcessiveRangeCheckInspection", "NoClassDefFoundError: could not initialize class com.intellij.psi.impl.JavaSimplePropertyGistKt"),

        // Java global inspections
        BlackListEntry.InspectionClass("com.intellij.codeInspection.IdempotentLoopBodyInspection", "depends on ProjectBytecodeAnalysis which is not available in LSP context"),
    )

    private class BlackList(
        entries: List<BlackListEntry>,
    ) {
        constructor(vararg entries: BlackListEntry) : this(entries.toList())

        private val implementationClasses = entries.mapTo(mutableSetOf()) { (it as? BlackListEntry.InspectionClass)?.inspectionClass }
        private val superClasses = entries.mapTo(mutableSetOf()) { (it as? BlackListEntry.InspectionSuperClass)?.superClass }

        fun containsImplementation(inspectionClass: String): Boolean {
            return inspectionClass in implementationClasses
        }

        fun containsSuperClass(inspectionInstance: Any): Boolean {
            return inspectionInstance::class.supertypes.any { (it.classifier as? KClass<*>)?.java?.name in superClasses }
        }
    }

    private sealed class BlackListEntry{
        abstract val reason: String

        protected fun ensureReason() {
            require(reason.isNotBlank()) { "inspectionClass must not be blank" }
        }

        data class InspectionClass(val inspectionClass: String, override val reason: String): BlackListEntry() {
            init {
                ensureReason()
            }
        }
        data class InspectionSuperClass(val superClass: String, override val reason: String): BlackListEntry() {
            init {
                ensureReason()
            }
        }
    }

    private fun getLocalInspections(language: Language): List<LocalInspectionTool> {
        return LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .asSequence()
            .filter { it.language == language.id }
            .filter { it.enabledByDefault }
            .filter { HighlightDisplayLevel.find(it.level) != HighlightDisplayLevel.DO_NOT_SHOW }
            .filterNot { blacklist.containsImplementation(it.implementationClass) }
            .mapNotNull { inspection ->
                runCatching {
                    inspection.instantiateTool()
                }.getOrHandleException {
                    if (LOG.isTraceEnabled) LOG.warn(it)
                    else LOG.warn(it.toString())
                }
            }
            .filterNot { blacklist.containsSuperClass(it) }
            .filterIsInstance<LocalInspectionTool>()
            .toList()
            .takeIf { it.isNotEmpty() }
            ?: emptyList()
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
                    if (LOG.isTraceEnabled) LOG.warn(it)
                    else LOG.warn(it.toString())
                }
            }
            .filterNot { blacklist.containsSuperClass(it) }
            .filterIsInstance<GlobalSimpleInspectionTool>()
            .toList()
            .takeIf { it.isNotEmpty() }
            ?: emptyList()
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
                    severity = problemDescriptor.highlightType.toLsp(),
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

    private fun ProblemDescriptor.createDiagnosticData(project: Project): InspectionDiagnosticData {
        return InspectionDiagnosticData(
            fixes = fixes.orEmpty()
                .mapNotNull { fix ->
                    val modCommand = getModCommand(fix, project, this) ?: return@mapNotNull null
                    val modCommandData = ModCommandData.from(modCommand) ?: return@mapNotNull null
                    InspectionQuickfixData(fix.name, modCommandData)
                }
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

private val LOG = logger<LSInspectionDiagnosticProviderImpl>()

// TODO LSP-241 design, currently some random conversions
private fun ProblemHighlightType.toLsp(): DiagnosticSeverity = when (this) {
    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> DiagnosticSeverity.Warning
    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> DiagnosticSeverity.Error
    ProblemHighlightType.LIKE_DEPRECATED -> DiagnosticSeverity.Warning
    ProblemHighlightType.LIKE_UNUSED_SYMBOL -> DiagnosticSeverity.Warning
    ProblemHighlightType.ERROR -> DiagnosticSeverity.Error
    ProblemHighlightType.WARNING -> DiagnosticSeverity.Warning
    ProblemHighlightType.GENERIC_ERROR -> DiagnosticSeverity.Error
    ProblemHighlightType.INFO -> DiagnosticSeverity.Information
    ProblemHighlightType.WEAK_WARNING -> DiagnosticSeverity.Warning
    ProblemHighlightType.INFORMATION -> DiagnosticSeverity.Hint
    ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL -> DiagnosticSeverity.Information
    ProblemHighlightType.POSSIBLE_PROBLEM -> DiagnosticSeverity.Warning
}

// TODO LSP-241 design, currently some random conversions
private fun ProblemHighlightType.toLspTags(): List<DiagnosticTag>? = when (this) {
    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> null
    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL -> null
    ProblemHighlightType.LIKE_DEPRECATED -> listOf(DiagnosticTag.Deprecated)
    ProblemHighlightType.LIKE_UNUSED_SYMBOL -> listOf(DiagnosticTag.Unnecessary)
    ProblemHighlightType.ERROR -> null
    ProblemHighlightType.WARNING -> null
    ProblemHighlightType.GENERIC_ERROR -> null
    ProblemHighlightType.INFO -> null
    ProblemHighlightType.WEAK_WARNING -> null
    ProblemHighlightType.INFORMATION -> null
    ProblemHighlightType.LIKE_MARKED_FOR_REMOVAL -> listOf(DiagnosticTag.Deprecated)
    ProblemHighlightType.POSSIBLE_PROBLEM -> null
}
