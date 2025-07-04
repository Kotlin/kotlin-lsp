// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.configuration

import com.jetbrains.ls.api.features.impl.common.definitions.LSDefinitionProviderCommonImpl
import com.jetbrains.ls.api.features.impl.common.diagnostics.LSSyntaxErrorDiagnosticProviderImpl
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSInspectionDiagnosticProviderImpl
import com.jetbrains.ls.api.features.impl.common.diagnostics.inspections.LSInspectionFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.apiImpl.lsApiKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.codeActions.LSOrganizeImportsCodeActionProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.codeActions.kotlinCodeActionsPlugins
import com.jetbrains.ls.api.features.impl.common.kotlin.codeStyle.kotlinCodeStylePlugin
import com.jetbrains.ls.api.features.impl.common.kotlin.completion.kotlinCompletionPlugin
import com.jetbrains.ls.api.features.impl.common.kotlin.completion.rekot.LSRekotBasedKotlinCompletionProviderImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.definitions.LSKotlinPackageDefinitionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsFixesCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler.LSKotlinCompilerDiagnosticsProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.intentions.LSKotlinIntentionCodeActionProviderImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.hover.LSHoverProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.kotlin.semanticTokens.LSSemanticTokensProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.signatureHelp.LSSignatureHelpKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.symbols.LSDocumentSymbolProviderKotlin
import com.jetbrains.ls.api.features.impl.common.kotlin.symbols.LSWorkspaceSymbolProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.usages.kotlinUsagesIjPlugins
import com.jetbrains.ls.api.features.impl.common.references.LSReferencesProviderCommonImpl
import com.jetbrains.ls.api.features.language.LSLanguageConfiguration
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.base.fir.codeInsight.FirCodeInsightForClassPath

val LSKotlinLanguageConfiguration: LSLanguageConfiguration = LSLanguageConfiguration(
    entries = listOf(
        LSOrganizeImportsCodeActionProviderKotlinImpl,
        LSRekotBasedKotlinCompletionProviderImpl,
        LSDefinitionProviderCommonImpl(setOf(LSKotlinLanguage)),
        LSHoverProviderKotlinImpl,
        LSKotlinPackageDefinitionProvider,
        LSSemanticTokensProviderKotlinImpl,
        LSReferencesProviderCommonImpl(setOf(LSKotlinLanguage)),
        LSInspectionDiagnosticProviderImpl(setOf(LSKotlinLanguage)),
        LSInspectionFixesCodeActionProvider(setOf(LSKotlinLanguage)),
        LSSyntaxErrorDiagnosticProviderImpl(setOf(LSKotlinLanguage)),
        LSKotlinCompilerDiagnosticsProvider,
        LSKotlinCompilerDiagnosticsFixesCodeActionProvider,
        LSWorkspaceSymbolProviderKotlinImpl,
        LSDocumentSymbolProviderKotlin,
        LSKotlinIntentionCodeActionProviderImpl,
        LSSignatureHelpKotlinImpl,
    ),
    plugins = listOf(
        lsApiKotlinImpl,
        kotlinCompletionPlugin,
        ijPluginByXml(
            "kotlin.base.fir.code-insight.xml",
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