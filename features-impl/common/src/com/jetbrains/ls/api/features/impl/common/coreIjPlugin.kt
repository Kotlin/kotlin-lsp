package com.jetbrains.ls.api.features.impl.common

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val coreIjPlugin: IdeaPluginDescriptorImpl = ijPluginByXml(
    "META-INF/language-server/features/common/core.xml"
)