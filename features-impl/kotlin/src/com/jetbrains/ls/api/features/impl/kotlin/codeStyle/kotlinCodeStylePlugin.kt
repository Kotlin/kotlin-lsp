// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.codeStyle

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml
import org.jetbrains.kotlin.idea.plugin.common.KotlinPluginCommonClassForClassPath

internal val kotlinCodeStylePlugin: PluginMainDescriptor =
    ijPluginByXml(
        "/META-INF/formatter.xml",
        classForClasspath = KotlinPluginCommonClassForClassPath::class.java,
        useFakePluginId = true,
    )