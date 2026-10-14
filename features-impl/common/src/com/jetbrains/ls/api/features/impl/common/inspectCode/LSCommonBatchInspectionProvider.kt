// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.inspectCode

import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.SingleRootFileViewProvider
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.InspectionProfilePatcher
import com.jetbrains.ls.api.core.features.LSInspectionMetadata
import com.jetbrains.ls.api.core.features.lsBatchInspectionsDispatcher
import com.jetbrains.ls.api.core.features.lsInspectionMetadata
import com.jetbrains.ls.api.core.features.lsRunInspectionsOn
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.SimpleDiagnosticData
import com.jetbrains.ls.api.features.inspectCode.InspectionInfo
import com.jetbrains.ls.api.features.inspectCode.LSBatchFileResult
import com.jetbrains.ls.api.features.inspectCode.LSBatchInspection
import com.jetbrains.ls.api.features.inspectCode.LSBatchInspectionProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.WeakHashMap

/**
 * The batch run of the local inspections of [supportedLanguages]: the same tools as [LSCommonInspectionDiagnosticProvider]
 * with the same [inspectionProfilePatcher], but `onTheFly = false` and on [lsBatchInspectionsDispatcher].
 *
 * A problem carries [batchData] and no fixes: a run over a project would register lazy fixes for thousands of problems,
 * each holding PSI. `LSCommonInspectionFixesCodeActionProvider` finds them when the client asks.
 *
 * The injected-language inspections ([com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInjectionInspectionDiagnosticProvider])
 * are not run.
 */
class LSCommonBatchInspectionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val inspectionProfilePatcher: InspectionProfilePatcher = InspectionProfilePatcher(),
) : LSBatchInspectionProvider {

    companion object {
        /** The data of every batch problem: the batch source, no fixes. */
        val batchData: JsonElement = LSP.json.encodeToJsonElement(
            SimpleDiagnosticData(diagnosticSource = LSCommonInspectionDiagnosticProvider.batchDiagnosticSource, fixes = emptyList())
        )
    }

    private val inspectionsLock = Mutex()

    /** By server: a configuration is one object per JVM, and the servers of a JVM (tests) may load other plugins. */
    private val inspectionsByServer = WeakHashMap<LSServer, List<LSBatchInspection>>()

    context(server: LSServer)
    override suspend fun inspectFile(uri: URI, inspectionIds: Set<String>?): LSBatchFileResult =
        server.withAnalysisContextAndFileSettings(uri) {
            val psiFile = readAction {
                val virtualFile = uri.findVirtualFile() ?: return@readAction null
                if (virtualFile.isDirectory || SingleRootFileViewProvider.isTooLargeForIntelligence(virtualFile)) return@readAction null
                virtualFile.findPsiFile(project)?.takeIf { ProblemHighlightFilter.shouldProcessFileInBatch(it) }
            } ?: return@withAnalysisContextAndFileSettings LSBatchFileResult.SKIPPED
            val document = readAction { psiFile.fileDocument }
            val diagnostics = lsRunInspectionsOn(
                psiFile = psiFile,
                language = psiFile.language,
                toLspRange = { it.toLspRange(document) },
                onTheFly = false,
                inspectionProfilePatcher = inspectionProfilePatcher,
                diagnosticDataFactory = { _, _ -> batchData },
                inspectionIds = inspectionIds,
                dispatcher = lsBatchInspectionsDispatcher,
            )
            LSBatchFileResult(diagnostics)
        }

    /** Computed once per server: it instantiates every tool of the languages. */
    context(server: LSServer)
    override suspend fun inspections(): List<LSBatchInspection> =
        inspectionsLock.withLock {
            inspectionsByServer[server] ?: server.withAnalysisContext {
                supportedLanguages
                    .flatMap { language -> lsInspectionMetadata(language.intellijLanguage, inspectionProfilePatcher).map { language to it } }
                    .groupBy { (_, metadata) -> metadata.id }
                    .map { (_, byLanguage) -> byLanguage.first().second.toBatchInspection(byLanguage.mapTo(HashSet()) { it.first }) }
            }.also { inspectionsByServer[server] = it }
        }
}

private fun LSInspectionMetadata.toBatchInspection(languages: Set<LSLanguage>): LSBatchInspection = LSBatchInspection(
    languages = languages,
    info = InspectionInfo(
        id = id,
        displayName = displayName,
        groupPath = groupPath,
        language = language,
        defaultLevel = defaultLevel,
        enabledByDefault = enabledByDefault,
        cleanupTool = cleanupTool,
        disabledInServer = disabledInServer,
    ),
    aliases = aliases,
)
