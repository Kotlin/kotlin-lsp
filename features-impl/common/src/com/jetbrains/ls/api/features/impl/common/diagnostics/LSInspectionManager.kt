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
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.lang.Language
import com.intellij.lang.LanguageMatcher
import com.intellij.lang.MetaLanguage
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.LocalQuickFixWithModCommandFallback
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.diagnostic.ReportingClassSubstitutor
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionDiagnosticProvider.Companion.diagnosticSource
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData

private val LOG = logger<LSInspectionManager>()

internal class LSInspectionManager(
    private val inspectionBlacklist: Blacklist = Blacklist(),
    private val quickFixBlacklist: Blacklist = Blacklist()) {
    
    internal fun getLocalInspections(psiFile: PsiFile, infoInspections: Boolean = false): List<LocalInspectionTool> {
        return getEnabledInspectionTools(LocalInspectionEP.LOCAL_INSPECTION.extensionList, psiFile.language, infoInspections)
            .filterIsInstance<LocalInspectionTool>()
            .filter { localInspectionTool -> localInspectionTool.isAvailableForFile(psiFile) }
            .toList()
    }

    internal fun getSimpleGlobalInspections(language: Language): List<GlobalSimpleInspectionTool> {
        return getEnabledInspectionTools(InspectionEP.GLOBAL_INSPECTION.extensionList, language)
            .filterIsInstance<GlobalSimpleInspectionTool>()
            .toList()
    }

    internal fun getSharedLocalInspectionsFromGlobalTools(language: Language, infoInspections: Boolean = false): List<LocalInspectionTool> {
        return getEnabledInspectionTools(InspectionEP.GLOBAL_INSPECTION.extensionList, language, infoInspections)
            .filterIsInstance<GlobalInspectionTool>()
            .mapNotNull { globalInspectionTool -> globalInspectionTool.sharedLocalInspectionTool }
            .filterNot { inspectionBlacklist.containsSuperClass(it) }
            .toList()
    }

    private fun getEnabledInspectionTools(extensionList: List<InspectionEP>, language: Language, infoInspections: Boolean = false):
            Sequence<InspectionProfileEntry> {
        return extensionList
            .asSequence()
            .filter { inspectionEP ->
                val inspectionLanguageId = inspectionEP.language ?: return@filter false
                isSupportAnyLanguage(inspectionLanguageId) || isLanguageSupportedByInspection(inspectionLanguageId, language)
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

    /** [com.intellij.lang.LanguageExtensionPoint.language] **/
    private fun isSupportAnyLanguage(inspectionLanguageId: String): Boolean = inspectionLanguageId.isEmpty()

    private fun isLanguageSupportedByInspection(inspectionLanguageId: String, fileLanguage: Language): Boolean {
        val inspectionLanguage = findLanguageOrMetaLanguageByID(inspectionLanguageId) ?: return false

        return LanguageMatcher.matchWithDialects(inspectionLanguage).matchesLanguage(fileLanguage)
    }

    private fun findLanguageOrMetaLanguageByID(languageId: String): Language? {
        return Language.findLanguageByID(languageId)
            ?: MetaLanguage.all().firstOrNull { it.id == languageId }
    }

    context(server: LSServer)
    internal fun createDiagnosticData(descriptor: ProblemDescriptor, project: Project): SimpleDiagnosticData {
        return SimpleDiagnosticData(
            diagnosticSource = diagnosticSource,
            fixes = descriptor.fixes.orEmpty().mapNotNull { quickFix ->
                val modCommand = getModCommand(quickFix, project, descriptor) ?: return@mapNotNull null
                val modCommandData = ModCommandData.from(modCommand, server) ?: return@mapNotNull null
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

        val context = ActionContext.from(problemDescriptor)
        if (fix is IntentionAction) {
            if (blacklistEntry != null) {
                LOG.trace("Quick fix $fixClass is an IntentionAction, but it is blacklisted because of ${blacklistEntry.reason}")
                return null
            }

            val modCommandAction = fix.asModCommandAction()
            if (modCommandAction != null && modCommandAction.getPresentation(context) != null) {
                return modCommandAction.perform(context)
            }
        }

        if (fix is LocalQuickFix) {
            if (blacklistEntry != null) {
                LOG.trace("Quick fix $fixClass is a LocalQuickFix, but it is blacklisted because of ${blacklistEntry.reason}")
                return null
            }


            val fallbackModCommandAction = LocalQuickFixWithModCommandFallback.getFallbackModCommandActionFor(fix)
            if (fallbackModCommandAction != null && fallbackModCommandAction.getPresentation(context) != null) {
                return fallbackModCommandAction.perform(context)
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
