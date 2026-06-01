// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.configuration

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.analyzer.plugins.makePlugin

internal val kotlinFeature: PluginMainDescriptor = makePlugin(
    pluginId = "org.jetbrains.ls.feature.kotlin",
    xmlModules = mapOf(
        "features/kotlin/core" to "META-INF/language-server/features/kotlin/lsApiKotlinImpl.xml",
        "features/kotlin/completion" to "META-INF/language-server/features/kotlin/completion.xml",
        "features/kotlin/codeActions" to "META-INF/language-server/features/kotlin/codeActions.xml",
        "ls.kotlin.searchingBase" to "META-INF/searching-base.xml",
        "features/kotlin/usages" to "META-INF/language-server/features/kotlin/usages.xml",
        "features/kotlin/import" to "META-INF/language-server/features/kotlin/workspace-import.xml",
        "ls.kotlin.formatter" to "META-INF/formatter.xml",
    ),
    dependencies = listOf(
        "org.jetbrains.ls.plugin.kotlin",
        "org.jetbrains.ls.feature.java-base"
    ),
)
