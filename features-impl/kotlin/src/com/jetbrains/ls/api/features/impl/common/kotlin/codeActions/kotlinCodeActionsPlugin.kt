package com.jetbrains.ls.api.features.impl.common.kotlin.codeActions

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UsePropertyAccessSyntaxInspection

internal val kotlinCodeActionsPlugins: List<IdeaPluginDescriptorImpl> = listOf(
    ijPluginByXml("META-INF/language-server/features/kotlin/codeActions.xml"),
    ijPluginByXml("kotlin.code-insight.inspections.k2.xml", classForClasspath = UsePropertyAccessSyntaxInspection::class.java),
    ijPluginByXml("kotlin.code-insight.inspections.shared.xml", classForClasspath = KotlinUnusedImportInspection::class.java),
)