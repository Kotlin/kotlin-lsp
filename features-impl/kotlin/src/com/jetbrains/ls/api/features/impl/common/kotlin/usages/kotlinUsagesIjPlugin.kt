package com.jetbrains.ls.api.features.impl.common.kotlin.usages

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.plugin.common.KotlinPluginCommonClassForClassPath
import org.jetbrains.kotlin.idea.searching.kmp.KotlinK2ExpectActualSupport

internal val kotlinUsagesIjPlugins: List<PluginMainDescriptor> by lazy {
    listOf(
        ijPluginByXml("META-INF/searching-base.xml", KotlinPluginCommonClassForClassPath::class.java, useFakePluginId = true),
        ijPluginByXml("META-INF/language-server/features/kotlin/usages.xml"),
        ijPluginByXml("kotlin.searching.k2.xml", KotlinK2ExpectActualSupport::class.java),
    )
}