// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeActions

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UsePropertyAccessSyntaxInspection

internal val kotlinCodeActionsPlugins: List<PluginMainDescriptor> = listOf(
  ijPluginByXml("META-INF/language-server/features/kotlin/codeActions.xml"),
  ijPluginByXml("intellij.kotlin.codeInsight.inspections.xml", classForClasspath = UsePropertyAccessSyntaxInspection::class.java),
  ijPluginByXml("kotlin.code-insight.inspections.shared.xml", classForClasspath = KotlinUnusedImportInspection::class.java),
)