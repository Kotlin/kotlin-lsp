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
import com.jetbrains.ls.api.features.impl.common.kotlin.hover.LSHoverProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.kotlin.semanticTokens.LSSemanticTokensProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.usages.kotlinUsagesIjPlugins
import com.jetbrains.ls.api.features.impl.common.kotlin.workspaceSymbols.LSWorkspaceSymbolProviderKotlinImpl
import com.jetbrains.ls.api.features.impl.common.references.LSReferencesProviderCommonImpl
import com.jetbrains.ls.api.features.language.LSLanguageConfiguration

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
    ),
    plugins = listOf(
        lsApiKotlinImpl,
        kotlinCompletionPlugin,
        *kotlinCodeActionsPlugins.toTypedArray(),
        *kotlinUsagesIjPlugins.toTypedArray(),
        kotlinCodeStylePlugin,
    ),
    languages = listOf(
        LSKotlinLanguage,
    ),
)