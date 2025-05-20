package com.jetbrains.ls.api.features.impl.common.search

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val commonUsagesPlugin: IdeaPluginDescriptorImpl =
    ijPluginByXml("META-INF/language-server/features/common/usages.xml")