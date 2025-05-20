package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val kotlinCompletionPlugin: PluginMainDescriptor = ijPluginByXml(
    "META-INF/language-server/features/kotlin/completion.xml"
)