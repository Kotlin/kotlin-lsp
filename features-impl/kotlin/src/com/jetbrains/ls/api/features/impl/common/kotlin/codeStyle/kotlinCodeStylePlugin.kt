package com.jetbrains.ls.api.features.impl.common.kotlin.codeStyle

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.plugin.common.KotlinPluginCommonClassForClassPath

internal val kotlinCodeStylePlugin: PluginMainDescriptor =
    ijPluginByXml(
        "/META-INF/formatter.xml",
        classForClasspath = KotlinPluginCommonClassForClassPath::class.java,
        useFakePluginId = true,
    )