// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.callHierarchy

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CallHierarchyIncomingCall
import com.jetbrains.lsp.protocol.CallHierarchyIncomingCallsParams
import com.jetbrains.lsp.protocol.CallHierarchyItem
import com.jetbrains.lsp.protocol.CallHierarchyOutgoingCall
import com.jetbrains.lsp.protocol.CallHierarchyOutgoingCallsParams
import com.jetbrains.lsp.protocol.CallHierarchyPrepareParams

interface LSCallHierarchyProvider : LSLanguageSpecificConfigurationEntry, LSUniqueConfigurationEntry {

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem>?

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun incomingCalls(params: CallHierarchyIncomingCallsParams): List<CallHierarchyIncomingCall>?

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun outgoingCalls(params: CallHierarchyOutgoingCallsParams): List<CallHierarchyOutgoingCall>?
}
