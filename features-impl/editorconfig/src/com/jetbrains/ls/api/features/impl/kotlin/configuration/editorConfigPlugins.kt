// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.configuration

import com.intellij.editorconfig.common.plugin.EditorConfigFileType
import com.intellij.editorconfig.common.plugin.EditorConfigParserDefinition
import com.intellij.editorconfig.common.syntax.EditorConfigLanguage
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.lang.LanguageParserDefinitions
import com.jetbrains.analyzer.bootstrap.AnalyzerContainerBuilder
import com.jetbrains.analyzer.bootstrap.AnalyzerFileTypeManager
import com.jetbrains.analyzer.plugins.makePlugin

val editorConfigPlugin: PluginMainDescriptor = makePlugin(
    pluginId = "org.editorconfig.editorconfigjetbrains",
    xmlModules = mapOf(
        "intellij.editorconfig.backend" to "/intellij.editorconfig.backend.xml",
        "intellij.editorconfig.common" to "/intellij.editorconfig.common.xml",
    )
)

@Suppress("UnusedReceiverParameter")
fun AnalyzerContainerBuilder.initEditorConfigApplicationContainer() {
    AnalyzerFileTypeManager.current.apply {
        registerFileType(EditorConfigFileType, "editorconfig")
    }
    LanguageParserDefinitions.INSTANCE.addExplicitExtension(EditorConfigLanguage, EditorConfigParserDefinition())
}