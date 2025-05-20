package com.jetbrains.ls.api.features.impl.common.codeStyle

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val commonCodeStylePlugin: PluginMainDescriptor = ijPluginByXml(
    "META-INF/language-server/features/common/codeStyle.xml"
)