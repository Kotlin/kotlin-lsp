package com.jetbrains.ls.api.features.impl.common.api

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val commonLsApiPlugin: IdeaPluginDescriptorImpl =
    ijPluginByXml("META-INF/language-server/features/common/commonLsApi.xml")