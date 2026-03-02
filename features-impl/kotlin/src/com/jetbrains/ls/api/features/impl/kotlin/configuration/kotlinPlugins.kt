// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.configuration

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.base.fir.codeInsight.FirCodeInsightForClassPath
import org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UsePropertyAccessSyntaxInspection
import org.jetbrains.kotlin.idea.plugin.common.KotlinPluginCommonClassForClassPath
import org.jetbrains.kotlin.idea.searching.kmp.KotlinK2ExpectActualSupport

internal val lsApiKotlinImpl: PluginMainDescriptor = ijPluginByXml(
    "META-INF/language-server/features/kotlin/lsApiKotlinImpl.xml"
)

internal val kotlinCompletionPlugin: PluginMainDescriptor = ijPluginByXml(
    "META-INF/language-server/features/kotlin/completion.xml"
)

internal val kotlinCodeActionsPlugins: List<PluginMainDescriptor> = listOf(
    ijPluginByXml("META-INF/language-server/features/kotlin/codeActions.xml"),
    ijPluginByXml("intellij.kotlin.codeInsight.inspections.xml", classForClasspath = UsePropertyAccessSyntaxInspection::class.java),
    ijPluginByXml("kotlin.code-insight.inspections.shared.xml", classForClasspath = KotlinUnusedImportInspection::class.java),
)

internal val kotlinUsagesIjPlugins: List<PluginMainDescriptor> by lazy {
    listOf(
        ijPluginByXml("META-INF/searching-base.xml", KotlinPluginCommonClassForClassPath::class.java, useFakePluginId = true),
        ijPluginByXml("META-INF/language-server/features/kotlin/usages.xml"),
        ijPluginByXml("intellij.kotlin.searching.xml", KotlinK2ExpectActualSupport::class.java),
    )
}

internal val kotlinCodeStylePlugin: PluginMainDescriptor =
    ijPluginByXml(
        "/META-INF/formatter.xml",
        classForClasspath = KotlinPluginCommonClassForClassPath::class.java,
        useFakePluginId = true,
    )

internal val kotlinFirCodeInsightPlugin: PluginMainDescriptor =
    ijPluginByXml(
        xmlResourcePath = "intellij.kotlin.codeInsight.base.xml",
        classForClasspath = FirCodeInsightForClassPath::class.java,
        useFakePluginId = true,
    )
