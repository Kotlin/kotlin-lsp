// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.callHierarchy

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.resolve.getConfigurationEntryId
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CallHierarchyIncomingCall
import com.jetbrains.lsp.protocol.CallHierarchyIncomingCallsParams
import com.jetbrains.lsp.protocol.CallHierarchyItem
import com.jetbrains.lsp.protocol.CallHierarchyOutgoingCall
import com.jetbrains.lsp.protocol.CallHierarchyOutgoingCallsParams
import com.jetbrains.lsp.protocol.CallHierarchyPrepareParams

object LSCallHierarchy {

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem>? {
        val providers = configuration.entriesFor<LSCallHierarchyProvider>(params.textDocument)
        return providers.firstNotNullOfOrNull { callHierarchyProvider -> callHierarchyProvider.prepareCallHierarchy(params) }
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall>? {
        val providerId = getConfigurationEntryId(params.item.data) ?: return null
        val provider = configuration.entryById<LSCallHierarchyProvider>(providerId) ?: return null
        return provider.incomingCalls(params)
    }

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun outgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall>? {
        val providerId = getConfigurationEntryId(params.item.data) ?: return null
        val provider = configuration.entryById<LSCallHierarchyProvider>(providerId) ?: return null
        return provider.outgoingCalls(params)
    }
}
