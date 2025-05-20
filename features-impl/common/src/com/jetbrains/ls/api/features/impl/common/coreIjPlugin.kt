package com.jetbrains.ls.api.features.impl.common

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val coreIjPlugin: PluginMainDescriptor = ijPluginByXml(
    "META-INF/language-server/features/common/core.xml"
)