// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.analyzer.plugins.makePlugin

val javaBaseFeature: PluginMainDescriptor = makePlugin(
    pluginId = "org.jetbrains.ls.feature.java-base",
    xmlModules = mapOf(
        "features/javaBase/apiImpl" to "META-INF/language-server/features/javaBase/lsApiJavaBaseImpl.xml"
    ),
    dependencies = listOf("org.jetbrains.ls.plugin.java"),
)

