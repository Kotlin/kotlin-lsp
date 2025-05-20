package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val commonCompletionPlugin: IdeaPluginDescriptorImpl =
    ijPluginByXml("/META-INF/language-server/features/common/completion.xml")