// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.utils.ijPluginByXml

internal val kotlinCompletionPlugin: PluginMainDescriptor = ijPluginByXml(
    "META-INF/language-server/features/kotlin/completion.xml"
)