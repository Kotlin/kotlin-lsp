package com.jetbrains.ls.api.features.language

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.LSConfigurationEntry

class LSLanguageConfiguration(
    val entries: List<LSConfigurationEntry>,
    val plugins: List<IdeaPluginDescriptorImpl>,
    val languages: List<LSLanguage>,
)