// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.definition

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.flow.Flow

interface LSDefinitionProvider : LSLanguageSpecificConfigurationEntry {
    context(_: LSServer)
    fun provideDefinitions(params: DefinitionParams): Flow<Location>
}