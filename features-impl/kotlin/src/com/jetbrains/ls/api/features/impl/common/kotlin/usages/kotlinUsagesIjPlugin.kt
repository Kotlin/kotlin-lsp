// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.usages

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.plugin.common.KotlinPluginCommonClassForClassPath
import org.jetbrains.kotlin.idea.searching.kmp.KotlinK2ExpectActualSupport

internal val kotlinUsagesIjPlugins: List<PluginMainDescriptor> by lazy {
    listOf(
      ijPluginByXml("META-INF/searching-base.xml", KotlinPluginCommonClassForClassPath::class.java, useFakePluginId = true),
      ijPluginByXml("META-INF/language-server/features/kotlin/usages.xml"),
      ijPluginByXml("intellij.kotlin.searching.xml", KotlinK2ExpectActualSupport::class.java),
    )
}