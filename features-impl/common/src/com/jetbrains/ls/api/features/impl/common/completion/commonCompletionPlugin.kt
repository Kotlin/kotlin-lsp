package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val commonCompletionPlugin: PluginMainDescriptor =
    ijPluginByXml("/META-INF/language-server/features/common/completion.xml")