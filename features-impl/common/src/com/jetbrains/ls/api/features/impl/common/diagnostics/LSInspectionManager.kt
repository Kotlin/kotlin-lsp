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
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.lang.Language
import com.intellij.lang.Language.findLanguageByID
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.diagnostic.ReportingClassSubstitutor
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionDiagnosticProvider.Companion.diagnosticSource
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData

private val LOG = logger<LSInspectionManager>()

internal class LSInspectionManager(
    private val inspectionBlacklist: Blacklist = Blacklist(),
    private val quickFixBlacklist: Blacklist = Blacklist()) {
    
    internal fun getLocalInspections(psiFile: PsiFile, infoInspections: Boolean = false): List<LocalInspectionTool> {
        return getEnabledInspectionTools(LocalInspectionEP.LOCAL_INSPECTION.extensionList, psiFile.language.id, infoInspections)
            .filterIsInstance<LocalInspectionTool>()
            .filter { localInspectionTool -> localInspectionTool.isAvailableForFile(psiFile) }
            .toList()
    }

    internal fun getSimpleGlobalInspections(language: Language): List<GlobalSimpleInspectionTool> {
        return getEnabledInspectionTools(InspectionEP.GLOBAL_INSPECTION.extensionList, language.id)
            .filterIsInstance<GlobalSimpleInspectionTool>()
            .toList()
    }

    internal fun getSharedLocalInspectionsFromGlobalTools(language: Language, infoInspections: Boolean = false): List<LocalInspectionTool> {
        return getEnabledInspectionTools(InspectionEP.GLOBAL_INSPECTION.extensionList, language.id, infoInspections)
            .filterIsInstance<GlobalInspectionTool>()
            .mapNotNull { globalInspectionTool -> globalInspectionTool.sharedLocalInspectionTool }
            .filterNot { inspectionBlacklist.containsSuperClass(it) }
            .toList()
    }

    private fun languageDialectIsSupportedByInspection(inspectionLanguageId: String?, fileLanguageId: String): Boolean {
        val fileLanguage = findLanguageByID(fileLanguageId)
        val inspectionLanguage = findLanguageByID(inspectionLanguageId)
        return fileLanguage?.isKindOf(inspectionLanguage) ?: false
    }

    /* Maybe this doesn't need now */
    private fun isLanguageInspection(inspectionEP: InspectionEP, languageId: String): Boolean {
        return inspectionEP.language?.uppercase() == "UAST" && (languageId == "JAVA" || languageId == "KT")
    }

    private fun getEnabledInspectionTools(extensionList: List<InspectionEP>, languageId: String, infoInspections: Boolean = false): 
            Sequence<InspectionProfileEntry> {
        return extensionList
            .asSequence()
            .filter { inspectionEP ->
                inspectionEP.language == languageId || languageDialectIsSupportedByInspection(
                    inspectionEP.language,
                    languageId
                ) || isLanguageInspection(inspectionEP, languageId)
            }
            .filter { inspectionEP -> inspectionEP.enabledByDefault }
            .filter { inspectionEP -> (HighlightDisplayLevel.find(inspectionEP.level) == HighlightDisplayLevel.DO_NOT_SHOW) == infoInspections }
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

    internal fun createDiagnosticData(descriptor: ProblemDescriptor, project: Project): SimpleDiagnosticData {
        return SimpleDiagnosticData(
            diagnosticSource = diagnosticSource,
            fixes = descriptor.fixes.orEmpty().mapNotNull { quickFix ->
                val modCommand = getModCommand(quickFix, project, descriptor) ?: return@mapNotNull null
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
                LOG.trace("Quick fix $fixClass is a ModCommandQuickFix, but it is blacklisted because of ${blacklistEntry.reason}")
                return null
            }

            return fix.perform(project, problemDescriptor)
        }

        if (fix is IntentionAction) {
            if (blacklistEntry != null) {
                LOG.trace("Quick fix $fixClass is an IntentionAction, but it is blacklisted because of ${blacklistEntry.reason}")
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

internal fun isSuppressed(
    localInspection: LocalInspectionTool,
    descriptor: ProblemDescriptor
): Boolean = runCatching {
    val element = descriptor.psiElement ?: descriptor.startElement
    element != null && localInspection.isSuppressedFor(element)
}.getOrHandleException {
    LOG.warn(it)
} ?: false
