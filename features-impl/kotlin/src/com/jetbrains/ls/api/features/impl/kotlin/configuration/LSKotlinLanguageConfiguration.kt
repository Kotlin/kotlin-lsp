// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.configuration

import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.jetbrains.analyzer.api.AnalyzerFileSystems
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.bootstrap.AnalyzerContext
import com.jetbrains.analyzer.kotlin.KotlinWorkspaceModelCaches
import com.jetbrains.analyzer.kotlin.createKotlinWorkspaceModelCaches
import com.jetbrains.analyzer.kotlin.initKotlinApplicationContainer
import com.jetbrains.analyzer.kotlin.initKotlinProjectContainer
import com.jetbrains.analyzer.kotlin.initKotlinWorkspaceModelCaches
import com.jetbrains.analyzer.kotlin.kotlinPlugin
import com.jetbrains.ls.api.features.WorkspaceComponentEntry
import com.jetbrains.ls.api.features.impl.common.definitions.LSCommonDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonIntentionFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonSyntaxErrorDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.foldingRange.LSCommonFoldingRangeProvider
import com.jetbrains.ls.api.features.impl.common.formatting.LSCommonFormattingProvider
import com.jetbrains.ls.api.features.impl.common.implementation.LSCommonImplementationProvider
import com.jetbrains.ls.api.features.impl.common.references.LSCommonReferencesProvider
import com.jetbrains.ls.api.features.impl.common.typeDefinition.LSCommonTypeDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.javaBase.LSJavaPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.LLFirSessionCacheStorageComponent
import com.jetbrains.ls.api.features.impl.kotlin.callHierarchy.LSKotlinCallHierarchyProvider
import com.jetbrains.ls.api.features.impl.kotlin.callHierarchy.LSKotlinCallHierarchyRenderer
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinOrganizeImportsCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.completion.LSKotlinCompletionProvider
import com.jetbrains.ls.api.features.impl.kotlin.definitions.LSKotlinPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.intentions.kotlinIntentionConverter
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.kotlinInspectionBlacklist
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.kotlinQuickFixBlacklist
import com.jetbrains.ls.api.features.impl.kotlin.hover.LSKotlinHoverProvider
import com.jetbrains.ls.api.features.impl.kotlin.inlayHints.LSKotlinInlayHintsProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.move.LSJvmMoveDirectoryProvider
import com.jetbrains.ls.api.features.impl.kotlin.rename.LSJvmRenameDirectoryProvider
import com.jetbrains.ls.api.features.impl.kotlin.rename.LSKotlinRenameProvider
import com.jetbrains.ls.api.features.impl.kotlin.semanticTokens.LSKotlinSemanticTokensProvider
import com.jetbrains.ls.api.features.impl.kotlin.signatureHelp.LSKotlinSignatureHelpProvider
import com.jetbrains.ls.api.features.impl.kotlin.symbols.LSKotlinDocumentSymbolProvider
import com.jetbrains.ls.api.features.impl.kotlin.symbols.LSKotlinWorkspaceSymbolProvider
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.snapshot.api.impl.core.AnalyzerContextKind
import com.jetbrains.ls.snapshot.api.impl.core.LSConfigurationData
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceComponent
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceEvent
import com.jetbrains.lsp.protocol.FoldingRangeKind
import org.jetbrains.kotlin.psi.KtImportList

val LSKotlinLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        WorkspaceComponentEntry { KotlinWorkspaceComponent },
        WorkspaceComponentEntry { LLFirSessionCacheStorageComponent },
        LSKotlinOrganizeImportsCodeActionProvider,
        LSKotlinCompletionProvider,
        LSCommonDefinitionProvider(setOf(LSKotlinLanguage), TargetKind.ALL),
        LSJavaPackageDefinitionProvider(setOf(LSKotlinLanguage), setOf(TargetKind.REFERENCE)),
        LSKotlinHoverProvider,
        LSKotlinPackageDefinitionProvider,
        LSKotlinSemanticTokensProvider,
        LSCommonReferencesProvider(setOf(LSKotlinLanguage), TargetKind.ALL),
        LSCommonInspectionDiagnosticProvider(
            supportedLanguages = setOf(LSKotlinLanguage),
            inspectionBlacklist = kotlinInspectionBlacklist,
            quickFixBlacklist = kotlinQuickFixBlacklist,
        ),
        LSCommonInspectionFixesCodeActionProvider(setOf(LSKotlinLanguage)),
        LSCommonIntentionFixesCodeActionProvider(
            supportedLanguages = setOf(LSKotlinLanguage),
            inspectionBlacklist = kotlinInspectionBlacklist,
            quickFixBlacklist = kotlinQuickFixBlacklist,
            converter = kotlinIntentionConverter()
        ),
        LSCommonSyntaxErrorDiagnosticProvider(setOf(LSKotlinLanguage)),
        LSKotlinCompilerDiagnosticsProvider,
        LSKotlinCompilerDiagnosticsFixesCodeActionProvider,
        LSKotlinWorkspaceSymbolProvider,
        LSKotlinDocumentSymbolProvider,
        LSKotlinSignatureHelpProvider,
        LSKotlinRenameProvider,
        LSJvmRenameDirectoryProvider,
        LSJvmMoveDirectoryProvider,
        LSCommonFormattingProvider(setOf(LSKotlinLanguage)),
        LSCommonImplementationProvider(setOf(LSKotlinLanguage)),
        LSCommonTypeDefinitionProvider(setOf(LSKotlinLanguage)),
        LSKotlinInlayHintsProvider,
        LSCommonFoldingRangeProvider(setOf(LSKotlinLanguage)) {
            when (it) {
                is PsiComment -> FoldingRangeKind.Comment
                is KtImportList -> FoldingRangeKind.Imports
                else -> FoldingRangeKind.Region
            }
        },
        LSKotlinCallHierarchyProvider,
        LSKotlinCallHierarchyRenderer
    ),
    plugins = listOf(
        kotlinPlugin,
        kotlinFeature,
    ),
    languages = listOf(
        LSKotlinLanguage,
    ),
)

private data class KotlinWorkspaceState(
    val caches: KotlinWorkspaceModelCaches? = null,
)

private object KotlinWorkspaceComponent : WorkspaceComponent<KotlinWorkspaceState> {
    override fun init(configData: LSConfigurationData): KotlinWorkspaceState =
        KotlinWorkspaceState()

    override fun handleEvent(event: WorkspaceEvent, state: KotlinWorkspaceState): KotlinWorkspaceState =
        when (event) {
            is WorkspaceEvent.WorkspaceModelChanged ->
                KotlinWorkspaceState(createKotlinWorkspaceModelCaches(AnalyzerContext.current.project))
            is WorkspaceEvent.InvalidateFiles, WorkspaceEvent.LowMemory -> state
        }

    override suspend fun registerInApplicationContainer(
        builder: AnalyzerContainerBuilder,
        application: Application,
        state: KotlinWorkspaceState,
        contextKind: AnalyzerContextKind,
    ) {
        val analyzerFileSystemsForIndexing = when (contextKind) {
            is AnalyzerContextKind.INDEXING -> contextKind.fileSystems ?: AnalyzerFileSystems.new()
            else -> null
        }
        builder.initKotlinApplicationContainer(analyzerFileSystemsForIndexing)
    }

    override suspend fun registerInProjectContainer(
        builder: AnalyzerContainerBuilder,
        project: Project,
        state: KotlinWorkspaceState,
        contextKind: AnalyzerContextKind,
    ) {
        builder.initKotlinProjectContainer(project)
        state.caches?.let { caches ->
            builder.initKotlinWorkspaceModelCaches(project, caches)
        }
    }
}
