// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.configuration

import com.jetbrains.analyzer.kotlin.initKotlinApplicationContainer
import com.jetbrains.analyzer.kotlin.initKotlinProjectContainer
import com.jetbrains.analyzer.kotlin.initKotlinWorkspaceModelCaches
import com.jetbrains.analyzer.kotlin.kotlinPlugins
import com.jetbrains.ls.api.features.ApplicationInitEntry
import com.jetbrains.ls.api.features.InvalidateHookEntry
import com.jetbrains.ls.api.features.ProjectInitEntry
import com.jetbrains.ls.api.features.RhizomeEntityTypeEntry
import com.jetbrains.ls.api.features.RhizomeLowMemoryWatcherHook
import com.jetbrains.ls.api.features.RhizomeWorkspaceInitEntry
import com.jetbrains.ls.api.features.impl.common.definitions.LSCommonDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonInspectionFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSCommonSyntaxErrorDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.formatting.LSCommonFormattingProvider
import com.jetbrains.ls.api.features.impl.common.implementation.LSCommonImplementationProvider
import com.jetbrains.ls.api.features.impl.common.references.LSCommonReferencesProvider
import com.jetbrains.ls.api.features.impl.common.typeDefinition.LSCommonTypeDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.impl.javaBase.LSJavaPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.KotlinWorkspaceModelEntity
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.LLFirSessionCacheStorageEntity
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.filesInvalidation
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.kotlinWorkspaceModel
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.lsApiKotlinImpl
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.registerLLFirSessionServices
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.resetKotlinWorkspaceModelEntity
import com.jetbrains.ls.api.features.impl.kotlin.apiImpl.resetLLFirSessionCacheEntity
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.LSKotlinOrganizeImportsCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.codeActions.kotlinCodeActionsPlugins
import com.jetbrains.ls.api.features.impl.kotlin.codeStyle.kotlinCodeStylePlugin
import com.jetbrains.ls.api.features.impl.kotlin.completion.LSKotlinCompletionProvider
import com.jetbrains.ls.api.features.impl.kotlin.completion.kotlinCompletionPlugin
import com.jetbrains.ls.api.features.impl.kotlin.definitions.LSKotlinPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.intentions.LSKotlinIntentionCodeActionProvider
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.kotlinInspectionBlacklist
import com.jetbrains.ls.api.features.impl.kotlin.diagnostics.kotlinQuickFixBlacklist
import com.jetbrains.ls.api.features.impl.kotlin.hover.LSKotlinHoverProvider
import com.jetbrains.ls.api.features.impl.kotlin.inlayHints.LSKotlinInlayHintsProvider
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.kotlin.rename.LSKotlinRenameProvider
import com.jetbrains.ls.api.features.impl.kotlin.semanticTokens.LSKotlinSemanticTokensProvider
import com.jetbrains.ls.api.features.impl.kotlin.signatureHelp.LSKotlinSignatureHelpProvider
import com.jetbrains.ls.api.features.impl.kotlin.symbols.LSKotlinDocumentSymbolProvider
import com.jetbrains.ls.api.features.impl.kotlin.symbols.LSKotlinWorkspaceSymbolProvider
import com.jetbrains.ls.api.features.impl.kotlin.usages.kotlinUsagesIjPlugins
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import com.jetbrains.ls.snapshot.api.impl.core.WorkspaceModelEntity
import org.jetbrains.kotlin.idea.base.fir.codeInsight.FirCodeInsightForClassPath

val LSKotlinLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        ApplicationInitEntry { _, _ ->
            initKotlinApplicationContainer()
        },
        ProjectInitEntry { project, type ->
            initKotlinProjectContainer(project)
            WorkspaceModelEntity.singleOrNull()?.let {
                it.kotlinWorkspaceModel?.let { model ->
                    initKotlinWorkspaceModelCaches(project, model.caches)
                }
            }
            LLFirSessionCacheStorageEntity.singleOrNull()?.let {
                registerLLFirSessionServices(project, type)
            }
        },
        RhizomeEntityTypeEntry { LLFirSessionCacheStorageEntity },
        RhizomeEntityTypeEntry { KotlinWorkspaceModelEntity },
        RhizomeWorkspaceInitEntry { resetLLFirSessionCacheEntity() },
        RhizomeWorkspaceInitEntry { resetKotlinWorkspaceModelEntity(it) },
        RhizomeLowMemoryWatcherHook { resetLLFirSessionCacheEntity() },
        InvalidateHookEntry { urls ->
            filesInvalidation(urls)
        },
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
        LSCommonSyntaxErrorDiagnosticProvider(setOf(LSKotlinLanguage)),
        LSKotlinCompilerDiagnosticsProvider,
        LSKotlinCompilerDiagnosticsFixesCodeActionProvider,
        LSKotlinWorkspaceSymbolProvider,
        LSKotlinDocumentSymbolProvider,
        LSKotlinIntentionCodeActionProvider,
        LSKotlinSignatureHelpProvider,
        LSKotlinRenameProvider,
        LSCommonFormattingProvider(setOf(LSKotlinLanguage)),
        LSCommonImplementationProvider(setOf(LSKotlinLanguage)),
        LSCommonTypeDefinitionProvider(setOf(LSKotlinLanguage)),
        LSKotlinInlayHintsProvider,
    ),
    plugins = listOf(
        *kotlinPlugins().toTypedArray(),
        lsApiKotlinImpl,
        kotlinCompletionPlugin,
        ijPluginByXml(
            xmlResourcePath = "intellij.kotlin.codeInsight.base.xml",
            classForClasspath = FirCodeInsightForClassPath::class.java,
            useFakePluginId = true,
        ),
        *kotlinCodeActionsPlugins.toTypedArray(),
        *kotlinUsagesIjPlugins.toTypedArray(),
        kotlinCodeStylePlugin,
    ),
    languages = listOf(
        LSKotlinLanguage,
    ),
)
