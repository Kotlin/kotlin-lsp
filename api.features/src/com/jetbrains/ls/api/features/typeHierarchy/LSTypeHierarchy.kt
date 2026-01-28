// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.typeHierarchy

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.TypeHierarchyItem
import com.jetbrains.lsp.protocol.TypeHierarchyPrepareParams
import com.jetbrains.lsp.protocol.TypeHierarchySubtypesParams
import com.jetbrains.lsp.protocol.TypeHierarchySupertypesParams

object LSTypeHierarchy {

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): List<TypeHierarchyItem>? {
        val providers = configuration.entriesFor<LSTypeHierarchyProvider>(params.textDocument)
        return providers.firstNotNullOfOrNull { typeHierarchyProvider -> typeHierarchyProvider.prepareTypeHierarchy(params) }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun supertypes(params: TypeHierarchySupertypesParams): List<TypeHierarchyItem>? {
        val providerId = getConfigurationEntryId(params.item.data) ?: return null
        val provider = configuration.entryById<LSTypeHierarchyProvider>(providerId) ?: return null
        return provider.supertypes(params)
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun subtypes(params: TypeHierarchySubtypesParams): List<TypeHierarchyItem>? {
        val providerId = getConfigurationEntryId(params.item.data) ?: return null
        val provider = configuration.entryById<LSTypeHierarchyProvider>(providerId) ?: return null
        return provider.subtypes(params)
    }
}
