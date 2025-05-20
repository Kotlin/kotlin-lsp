package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val kotlinCompletionPlugin: IdeaPluginDescriptorImpl = ijPluginByXml(
    "META-INF/language-server/features/kotlin/completion.xml"
)