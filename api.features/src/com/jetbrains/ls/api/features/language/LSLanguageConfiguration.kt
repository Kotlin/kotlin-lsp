package com.jetbrains.ls.api.features.language

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.LSConfigurationEntry

class LSLanguageConfiguration(
    val entries: List<LSConfigurationEntry>,
    val plugins: List<PluginMainDescriptor>,
    val languages: List<LSLanguage>,
)