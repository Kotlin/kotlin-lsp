package com.jetbrains.ls.api.features

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

val lsApiPlugin: PluginMainDescriptor = ijPluginByXml("META-INF/language-server/features/api/lsApi.xml")