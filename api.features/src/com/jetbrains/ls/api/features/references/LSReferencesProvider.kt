package com.jetbrains.ls.api.features.references

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.ReferenceParams
import kotlinx.coroutines.flow.Flow

interface LSReferencesProvider : LSLanguageSpecificConfigurationEntry {
    context(LSServer)
    fun getReferences(params: ReferenceParams): Flow<Location>
}
