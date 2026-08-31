// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.symbols

import com.jetbrains.ls.api.core.features.LSWorkspaceSymbolCustomizer
import com.jetbrains.ls.api.features.impl.common.symbols.LSWorkspaceSymbolProviderBase
import com.jetbrains.ls.api.features.impl.kotlin.psi.LSKotlinWorkspaceSymbolCustomizer

internal object LSKotlinWorkspaceSymbolProvider : LSWorkspaceSymbolProviderBase() {
    override fun createCustomizer(): LSWorkspaceSymbolCustomizer = LSKotlinWorkspaceSymbolCustomizer()
}
