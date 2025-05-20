package com.jetbrains.ls.api.features.impl.common.search

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val commonUsagesPlugin: PluginMainDescriptor =
    ijPluginByXml("META-INF/language-server/features/common/usages.xml")