// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.ls.api.features.language.LSLanguage

interface LSConfigurationEntry

interface LSLanguageSpecificConfigurationEntry : LSConfigurationEntry {
    val supportedLanguages: Set<LSLanguage>
}

interface ApplicationInitProvider : LSConfigurationEntry {
    val applicationInit: AnalyzerContainerBuilder.(Application) -> Unit
}

interface ProjectInitProvider : LSConfigurationEntry {
    val projectInit: AnalyzerContainerBuilder.(Project) -> Unit
}
