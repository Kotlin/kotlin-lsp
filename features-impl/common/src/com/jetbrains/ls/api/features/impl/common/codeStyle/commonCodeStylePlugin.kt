package com.jetbrains.ls.api.features.impl.common.codeStyle

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val commonCodeStylePlugin: IdeaPluginDescriptorImpl = ijPluginByXml(
    "META-INF/language-server/features/common/codeStyle.xml"
)