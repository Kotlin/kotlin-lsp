// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.lang.Language
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.diagnostics.LSDiagnosticProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.reflect.KClass

class LSInspectionDiagnosticProviderImpl(
    override val supportedLanguages: Set<LSLanguage>,
): LSDiagnosticProvider {
    context(LSServer)
    override fun getDiagnostics(params: DocumentDiagnosticParams): Flow<Diagnostic> = flow {
        val onTheFly = false
        withAnalysisContext {
            runReadAction c@{
                val file = params.textDocument.findVirtualFile() ?: return@c emptyList()
                val psiFile = file.findPsiFile(project) ?: return@c emptyList()
                val inspections = getInspections(psiFile.language).ifEmpty { return@c emptyList() }
                val holder = ProblemsHolder(InspectionManagerEx(project), psiFile, onTheFly)
                val visitors = inspections.map { inspection ->
                    InspectionWithVisitor(inspection, inspection.buildVisitor(holder, onTheFly))
                }

                collectDiagnostics(holder, psiFile, file, visitors)
            }
        }.forEach { emit(it) }
    }



    // kotlin ones should be moved closer to the kotlin impl
    private val blacklist = BlackList(
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveRedundantQualifierNameInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.UnusedVariableInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveExplicitTypeArgumentsInspection", "slow"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.K2MemberVisibilityCanBePrivateInspection", "slow, performs find usages"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.VariableNeverReadInspection", "very slow, uses extended checkers"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection", "very slow, uses extended checkers"),

        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.expressions.ExplicitThisInspection", "too noisy https://github.com/Kotlin/kotlin-lsp/issues/20"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.ImplicitThisInspection", "too noisy https://github.com/Kotlin/kotlin-lsp/issues/20"),
        BlackListEntry.InspectionClass("org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PublicApiImplicitTypeInspection", "too noisy https://github.com/Kotlin/kotlin-lsp/issues/4"),

        BlackListEntry.InspectionSuperClass("org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase", "they are slow as calling additional diagnostic collection"),
        BlackListEntry.InspectionSuperClass("org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase", "they are slow as calling additional diagnostic collection"),
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

    private fun getInspections(language: Language): List<LocalInspectionTool> {
        return LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { it.language == language.id }
            .filterNot { blacklist.containsImplementation(it.implementationClass) }
            .mapNotNull { inspection ->
                runCatching {
                    Class.forName(inspection.implementationClass).getConstructor().newInstance()
                }.getOrLogException { LOG.warn(it) }
            }
            .filterNot { blacklist.containsSuperClass(it) }
            .filterIsInstance<LocalInspectionTool>()
    }

    context(LSAnalysisContext, LSServer)
    private fun collectDiagnostics(
        holder: ProblemsHolder,
        psiFile: PsiFile,
        file: VirtualFile,
        visitors: List<InspectionWithVisitor>,
    ): List<Diagnostic> {
        val results = mutableListOf<Diagnostic>()

        fun collect(element: PsiElement) {
            for ((inspection, visitor) in visitors) {
                runCatching {
                    element.accept(visitor)
                }.getOrLogException { LOG.warn(it) }
                if (holder.hasResults()) {
                    results += holder.collectDiagnostics(file, inspection, element)
                }
                holder.clearResults()
            }
        }

        psiFile.accept(object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                collect(element)
                element.acceptChildren(this)
            }
        })

        return results
    }

    private data class InspectionWithVisitor(
        val inspection: LocalInspectionTool,
        val visitor: PsiElementVisitor,
    )

    context(LSAnalysisContext, LSServer)
    private fun ProblemsHolder.collectDiagnostics(
        file: VirtualFile,
        localInspectionTool: LocalInspectionTool,
        element: PsiElement
    ): List<Diagnostic> {
        val document = file.findDocument() ?: return emptyList()
        return results.mapNotNull { result ->
            val data = result.createDiagnosticData(localInspectionTool, element, file)
            Diagnostic(
                range = result.range()?.toLspRange(document) ?: return@mapNotNull null,
                severity = result.highlightType.toLsp(),
                // todo handle markers from [com.intellij.codeInspection.CommonProblemDescriptor.getDescriptionTemplate]
                message = result.tooltipTemplate,
                code = StringOrInt.string(localInspectionTool.id),
                tags = result.highlightType.toLspTags(),
                data = LSP.json.encodeToJsonElement(data),
            )
        }
    }

    context(LSAnalysisContext, LSServer)
    private fun ProblemDescriptor.range(): TextRange? {
        val element = psiElement ?: return null
        val elementRange = element.textRange ?: return null
        // relative range -> absolute range
        textRangeInElement?.let { return it.shiftRight(elementRange.startOffset) }
        return elementRange
    }


    context(LSAnalysisContext, LSServer)
    private fun ProblemDescriptor.createDiagnosticData(
        localInspectionTool: LocalInspectionTool,
        element: PsiElement,
        file: VirtualFile
    ): InspectionDiagnosticData? {
        return InspectionDiagnosticData(
            fixes = fixes.orEmpty().map { fix ->
                InspectionQuickfixData.createByFix(fix, localInspectionTool, element, file)
            }
        )
    }
}

private val LOG = logger<LSInspectionDiagnosticProviderImpl>()


// TODO design, currently some random conversions
private fun ProblemHighlightType.toLsp(): DiagnosticSeverity = when (this) {
    ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> DiagnosticSeverity.Hint
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

// TODO design, currently some random conversions
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