// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.jetbrains.ls.api.core.features.LSWorkspaceSymbolCustomizer
import com.jetbrains.ls.api.features.LSConfigurationEntry

interface LSWorkspaceSymbolCustomizerProvider : LSConfigurationEntry {
    fun createCustomizer(): LSWorkspaceSymbolCustomizer
}
