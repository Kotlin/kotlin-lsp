// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.configuration

import com.jetbrains.ls.api.features.impl.common.definitions.LSCommonDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSSyntaxErrorDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSCommonInspectionDiagnosticProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSCommonInspectionFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.formatting.LSCommonFormattingProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.apiImpl.lsApiKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.codeActions.LSKotlinOrganizeImportsCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.codeActions.kotlinCodeActionsPlugins
import com.jetbrains.ls.api.features.impl.common.kotlin.codeStyle.kotlinCodeStylePlugin
import com.jetbrains.ls.api.features.impl.common.kotlin.completion.LSKotlinCompletionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.completion.kotlinCompletionPlugin
import com.jetbrains.ls.api.features.impl.common.kotlin.definitions.LSKotlinPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.intentions.LSKotlinIntentionCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.kotlinInspectionBlacklist
import com.jetbrains.ls.api.features.impl.common.kotlin.hover.LSKotlinHoverProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.inlayHints.LSKotlinInlayHintsProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.kotlin.rename.LSKotlinRenameProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.semanticTokens.LSKotlinSemanticTokensProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.signatureHelp.LSKotlinSignatureHelpProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.symbols.LSKotlinDocumentSymbolProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.symbols.LSKotlinWorkspaceSymbolProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.usages.kotlinUsagesIjPlugins
import com.jetbrains.ls.api.features.impl.common.references.LSCommonReferencesProvider
import com.jetbrains.ls.api.features.impl.common.typeDefinition.LSCommonTypeDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.base.fir.codeInsight.FirCodeInsightForClassPath

val LSKotlinLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        LSKotlinOrganizeImportsCodeActionProvider,
        LSKotlinCompletionProvider,
        LSCommonDefinitionProvider(setOf(LSKotlinLanguage), TargetKind.ALL),
        LSKotlinHoverProvider,
        LSKotlinPackageDefinitionProvider,
        LSKotlinSemanticTokensProvider,
        LSCommonReferencesProvider(setOf(LSKotlinLanguage), TargetKind.ALL),
        LSCommonInspectionDiagnosticProvider(setOf(LSKotlinLanguage), inspectionBlacklist = kotlinInspectionBlacklist),
        LSCommonInspectionFixesCodeActionProvider(setOf(LSKotlinLanguage)),
        LSSyntaxErrorDiagnosticProvider(setOf(LSKotlinLanguage)),
        LSKotlinCompilerDiagnosticsProvider,
        LSKotlinCompilerDiagnosticsFixesCodeActionProvider,
        LSKotlinWorkspaceSymbolProvider,
        LSKotlinDocumentSymbolProvider,
        LSKotlinIntentionCodeActionProvider,
        LSKotlinSignatureHelpProvider,
        LSKotlinRenameProvider,
        LSCommonFormattingProvider(setOf(LSKotlinLanguage)),
        LSCommonTypeDefinitionProvider(setOf(LSKotlinLanguage)),
        LSKotlinInlayHintsProvider,
    ),
    plugins = listOf(
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