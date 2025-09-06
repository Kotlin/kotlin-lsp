// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.language

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.LSConfigurationEntry

class LSConfigurationPiece(
    val entries: List<LSConfigurationEntry> = emptyList(),
    val plugins: List<PluginMainDescriptor> = emptyList(),
    val languages: List<LSLanguage> = emptyList(),
)