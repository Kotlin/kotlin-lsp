package com.jetbrains.ls.api.features

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val lsApiPlugin: IdeaPluginDescriptorImpl = ijPluginByXml("META-INF/language-server/features/api/lsApi.xml")