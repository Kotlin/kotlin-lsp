// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.configuration

import com.jetbrains.ls.api.features.impl.common.definitions.LSDefinitionProviderCommonImpl
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSSyntaxErrorDiagnosticProviderImpl
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSInspectionDiagnosticProviderImpl
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSInspectionFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.formatting.LSFormattingProviderCommonImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.apiImpl.lsApiKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.codeActions.LSOrganizeImportsCodeActionProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.codeActions.kotlinCodeActionsPlugins
import com.jetbrains.ls.api.features.impl.common.kotlin.codeStyle.kotlinCodeStylePlugin
import com.jetbrains.ls.api.features.impl.common.kotlin.completion.LSCompletionProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.completion.kotlinCompletionPlugin
import com.jetbrains.ls.api.features.impl.common.kotlin.definitions.LSKotlinPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.intentions.LSKotlinIntentionCodeActionProviderImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.kotlinInspectionBlacklist
import com.jetbrains.ls.api.features.impl.common.kotlin.hover.LSHoverProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.inlayHints.LSInlayHintsKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.kotlin.semanticTokens.LSKotlinSemanticTokensProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.signatureHelp.LSKotlinSignatureHelpProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.symbols.LSDocumentSymbolProviderKotlin
import com.jetbrains.ls.api.features.impl.common.kotlin.symbols.LSKotlinWorkspaceSymbolProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.usages.kotlinUsagesIjPlugins
import com.jetbrains.ls.api.features.impl.common.references.LSReferencesProviderCommonImpl
import com.jetbrains.ls.api.features.impl.common.rename.LSRenameProviderCommonImpl
import com.jetbrains.ls.api.features.impl.common.utils.TargetKind
import com.jetbrains.ls.api.features.language.LSConfigurationPiece
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.base.fir.codeInsight.FirCodeInsightForClassPath

val LSKotlinLanguageConfiguration: LSConfigurationPiece = LSConfigurationPiece(
    entries = listOf(
        LSOrganizeImportsCodeActionProviderKotlinImpl,
        LSCompletionProviderKotlinImpl,
        LSDefinitionProviderCommonImpl(setOf(LSKotlinLanguage), TargetKind.ALL),
        LSHoverProviderKotlinImpl,
        LSKotlinPackageDefinitionProvider,
        LSKotlinSemanticTokensProvider,
        LSReferencesProviderCommonImpl(setOf(LSKotlinLanguage), TargetKind.ALL),
        LSInspectionDiagnosticProviderImpl(setOf(LSKotlinLanguage), blacklist = kotlinInspectionBlacklist),
        LSInspectionFixesCodeActionProvider(setOf(LSKotlinLanguage)),
        LSSyntaxErrorDiagnosticProviderImpl(setOf(LSKotlinLanguage)),
        LSKotlinCompilerDiagnosticsProvider,
        LSKotlinCompilerDiagnosticsFixesCodeActionProvider,
        LSKotlinWorkspaceSymbolProvider,
        LSDocumentSymbolProviderKotlin,
        LSKotlinIntentionCodeActionProviderImpl,
        LSKotlinSignatureHelpProvider,
        LSRenameProviderCommonImpl(setOf(LSKotlinLanguage)),
        LSFormattingProviderCommonImpl(setOf(LSKotlinLanguage)),
        LSInlayHintsKotlinImpl,
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