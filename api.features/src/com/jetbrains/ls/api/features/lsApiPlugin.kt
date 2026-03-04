// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.analyzer.plugins.makePlugin

val lsApiPlugin: PluginMainDescriptor = makePlugin(
    pluginId = "ls.api",
    xmlModules = mapOf(
        "language-server/features/api" to "META-INF/language-server/features/api/lsApi.xml"
    ),
)