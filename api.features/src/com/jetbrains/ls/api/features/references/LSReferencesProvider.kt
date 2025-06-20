// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.references

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.ReferenceParams
import kotlinx.coroutines.flow.Flow

interface LSReferencesProvider : LSLanguageSpecificConfigurationEntry {
    context(_: LSServer)
    fun getReferences(params: ReferenceParams): Flow<Location>
}
