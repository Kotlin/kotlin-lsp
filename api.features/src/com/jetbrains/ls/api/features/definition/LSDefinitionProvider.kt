package com.jetbrains.ls.api.features.definition

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.flow.Flow

interface LSDefinitionProvider : LSLanguageSpecificConfigurationEntry {
    context(LSServer)
    fun provideDefinitions(params: DefinitionParams): Flow<Location>
}