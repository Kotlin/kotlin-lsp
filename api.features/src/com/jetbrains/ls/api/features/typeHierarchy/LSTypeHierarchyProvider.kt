// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.typeHierarchy

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.TypeHierarchyItem
import com.jetbrains.lsp.protocol.TypeHierarchyPrepareParams
import com.jetbrains.lsp.protocol.TypeHierarchySubtypesParams
import com.jetbrains.lsp.protocol.TypeHierarchySupertypesParams

interface LSTypeHierarchyProvider : LSLanguageSpecificConfigurationEntry, LSUniqueConfigurationEntry {

    context(_: LSServer, _: LspHandlerContext)
    suspend fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): List<TypeHierarchyItem>?

    context(_: LSServer, _: LspHandlerContext)
    suspend fun supertypes(params: TypeHierarchySupertypesParams): List<TypeHierarchyItem>?

    context(_: LSServer, _: LspHandlerContext)
    suspend fun subtypes(params: TypeHierarchySubtypesParams): List<TypeHierarchyItem>?
}
