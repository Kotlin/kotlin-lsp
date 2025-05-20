package com.jetbrains.ls.api.features.impl.common.kotlin.apiImpl

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val lsApiKotlinImpl: PluginMainDescriptor = ijPluginByXml("META-INF/language-server/features/kotlin/lsApiKotlinImpl.xml")