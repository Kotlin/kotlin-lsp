// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.dap

import com.intellij.ide.plugins.PluginMainDescriptor
import com.jetbrains.ls.api.features.LSConfigurationEntry

class DapPluginsProvider(
    val plugins: List<PluginMainDescriptor> = emptyList(),
): LSConfigurationEntry